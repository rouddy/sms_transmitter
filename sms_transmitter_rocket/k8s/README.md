# Kubernetes deployment (namespace: `sms-transmitter`)

Manifests that deploy the `roudry/sms_transmitter` image **for in-cluster use only**.
The DB is kept on a PVC, and the Firebase service account JSON and the auth public key are injected as Secrets.

## Files

| File | Role |
|---|---|
| `namespace.yaml` | `sms-transmitter` namespace (uses `-` per k8s naming rules) |
| `pvc.yaml` | 1Gi PersistentVolumeClaim for the SQLite DB (ReadWriteOnce) |
| `deployment.yaml` | Single-replica Deployment (Recreate strategy) |
| `service.yaml` | ClusterIP service — reachable only inside the cluster, port 54323 |

Nothing is exposed externally: no `LoadBalancer`/`NodePort`/`Ingress` is created, so it cannot be reached from outside the cluster.

## Deployment steps

### 1. Image

Build and push the image with `docker/build_and_push.sh` (the tag is taken from the version in `Cargo.toml`).
Set the `image:` tag in `deployment.yaml` to the pushed version.

### 2. Firebase service account JSON → Secret

`firebase-credentials.json` is not in git, so create it as a k8s Secret
(run from the crate root, where the credentials file is):

```bash
kubectl -n sms-transmitter create secret generic firebase-credentials \
  --from-file=firebase-credentials.json=./firebase-credentials.json
```

The Secret's `firebase-credentials.json` key is mounted at `/app/secrets/firebase-credentials.json`,
and `FIREBASE_CREDENTIALS_PATH` in `deployment.yaml` points to that path.

> Never commit the Secret to git. It is not included in the image either (excluded by `.dockerignore`).

### 3. Auth public key → Secret

From the key pair created by `script/generate_keys.sh`, create a Secret with **only the public key**.
The server does not need the private key, so do not upload it to the cluster.

```bash
kubectl -n sms-transmitter create secret generic auth-public-key \
  --from-file=public_key.pem=./keys/public_key.pem
```

During key rotation (`generate_keys.sh --rotate`), include the previous public key as well:

```bash
kubectl -n sms-transmitter create secret generic auth-public-key \
  --from-file=public_key.pem=./keys/public_key.pem \
  --from-file=public_key.previous.pem=./keys/public_key.previous.pem \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n sms-transmitter rollout restart deployment/sms-transmitter
```

The Secret is mounted at `/app/keys`, and `AUTH_KEYS_DIR` points to that path.
The server does not start without `public_key.pem`.

### 4. Apply

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/pvc.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

Or all at once: `kubectl apply -f k8s/`

## Migrating existing DB data (optional)

The PVC starts empty. To bring over data from an existing `db/sms_transmitter.db`:

```bash
# 1) Once the pod is Running, copy the DB file
POD=$(kubectl -n sms-transmitter get pod -l app=sms-transmitter -o jsonpath='{.items[0].metadata.name}')
kubectl -n sms-transmitter cp ./db/sms_transmitter.db $POD:/app/db/sms_transmitter.db

# 2) Restart the pod so the server reopens the replaced file cleanly
kubectl -n sms-transmitter rollout restart deployment/sms-transmitter
```

The image does not include `db/` or credentials. If you don't need to migrate data,
skip this step; migrations create the schema automatically on startup.

## In-cluster access

From another pod:

```
http://sms-transmitter.sms-transmitter.svc.cluster.local:54323
```

GraphiQL UI: `http://sms-transmitter.sms-transmitter.svc.cluster.local:54323/graphiql`

Calls to `/graphql` need an `Authorization: Bearer <JWT>` header (generate one with `script/make_jwt.sh`).

## Notes

- Because of SQLite (single writer) and the RWO volume, the deployment uses `replicas: 1` and `strategy: Recreate`.
- The image tag is pinned, so update the tag in `deployment.yaml` whenever you push a new image.
- The PVC does not set `storageClassName`, so it uses the cluster's default StorageClass.
  If the cluster has no default StorageClass, set one explicitly in `pvc.yaml`.
