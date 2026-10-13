# Kubernetes 배포 (namespace: `sms-transmitter`)

`roudry/sms_transmitter` 이미지를 **클러스터 내부 전용**으로 배포하는 매니페스트다.
DB는 PVC로 유지하고, Firebase 서비스 계정 JSON과 인증 공개키는 Secret으로 주입한다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| `namespace.yaml` | `sms-transmitter` 네임스페이스 (k8s 이름 규칙상 `-` 사용) |
| `pvc.yaml` | SQLite DB용 1Gi PersistentVolumeClaim (ReadWriteOnce) |
| `deployment.yaml` | 단일 replica Deployment (Recreate 전략) |
| `service.yaml` | ClusterIP 서비스 — 클러스터 내부에서만 접근, 포트 54323 |

외부 노출은 없다: `LoadBalancer`/`NodePort`/`Ingress`를 만들지 않으므로 클러스터 밖에서는 접근 불가.

## 배포 순서

### 1. 이미지

`docker/build_and_push.sh`로 이미지를 빌드+푸시한다(태그는 `Cargo.toml` 버전에서 추출).
`deployment.yaml`의 `image:` 태그를 푸시한 버전과 맞춘다.

### 2. Firebase 서비스 계정 JSON → Secret

`firebase-credentials.json`은 git에 포함하지 않으므로 k8s Secret으로 생성한다
(크레딧셜 파일이 있는 크레이트 루트에서 실행):

```bash
kubectl -n sms-transmitter create secret generic firebase-credentials \
  --from-file=firebase-credentials.json=./firebase-credentials.json
```

이 Secret의 `firebase-credentials.json` 키가 `/app/secrets/firebase-credentials.json`으로
마운트되고, `deployment.yaml`의 `FIREBASE_CREDENTIALS_PATH`가 그 경로를 가리킨다.

> Secret은 git에 커밋하지 말 것. 이미지에도 포함되지 않는다(`.dockerignore`가 제외).

### 3. 인증 공개키 → Secret

`script/generate_keys.sh`로 만든 키쌍 중 **공개키만** Secret으로 만든다.
개인키는 서버에 필요 없으므로 클러스터에 올리지 않는다.

```bash
kubectl -n sms-transmitter create secret generic auth-public-key \
  --from-file=public_key.pem=./keys/public_key.pem
```

키 교체(`generate_keys.sh --rotate`) 중에는 이전 공개키도 함께 넣는다:

```bash
kubectl -n sms-transmitter create secret generic auth-public-key \
  --from-file=public_key.pem=./keys/public_key.pem \
  --from-file=public_key.previous.pem=./keys/public_key.previous.pem \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n sms-transmitter rollout restart deployment/sms-transmitter
```

Secret이 `/app/keys`로 마운트되고 `AUTH_KEYS_DIR`이 그 경로를 가리킨다.
`public_key.pem`이 없으면 서버가 시작되지 않는다.

### 4. 적용

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/pvc.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

또는 한 번에: `kubectl apply -f k8s/`

## 기존 DB 데이터 이전 (선택)

PVC는 최초에 비어 있다. 기존 `db/sms_transmitter.db`의 데이터를 가져오려면:

```bash
# 1) 파드가 Running이 된 뒤 DB 파일을 복사
POD=$(kubectl -n sms-transmitter get pod -l app=sms-transmitter -o jsonpath='{.items[0].metadata.name}')
kubectl -n sms-transmitter cp ./db/sms_transmitter.db $POD:/app/db/sms_transmitter.db

# 2) 파일이 열려 있던 상태를 정리하도록 파드 재시작
kubectl -n sms-transmitter rollout restart deployment/sms-transmitter
```

이미지에는 `db/`와 자격증명이 포함되지 않는다. 데이터 이전이 필요 없으면
이 단계를 건너뛰어도 되고, 시작 시 마이그레이션이 스키마를 자동 생성한다.

## 클러스터 내부 접근

다른 파드에서:

```
http://sms-transmitter.sms-transmitter.svc.cluster.local:54323
```

GraphiQL UI: `http://sms-transmitter.sms-transmitter.svc.cluster.local:54323/graphiql`

`/graphql` 호출에는 `Authorization: Bearer <JWT>` 헤더가 필요하다(`script/make_jwt.sh`로 생성).

## 주의

- SQLite(단일 writer) + RWO 볼륨 제약 때문에 `replicas: 1`, `strategy: Recreate`를 사용한다.
- 이미지 태그가 고정되어 있으므로 새 이미지를 푸시할 때 `deployment.yaml`의 태그를 갱신할 것.
- PVC에 `storageClassName`을 지정하지 않아 클러스터 기본 StorageClass를 사용한다.
  기본 StorageClass가 없는 클러스터라면 `pvc.yaml`에 명시적으로 지정할 것.