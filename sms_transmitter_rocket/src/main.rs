#[macro_use]
extern crate rocket;

use async_graphql::Schema;
use async_graphql_rocket::{GraphQLQuery, GraphQLRequest, GraphQLResponse};
use rocket::State;
use sea_orm_migration::MigratorTrait;
use std::sync::Arc;

mod config;
mod db;
mod entity;
mod firebase;
mod graphql;
mod migration;

use graphql::{AppSchema, MutationRoot, QueryRoot};

#[get("/graphql?<query..>")]
async fn graphql_query(schema: &State<AppSchema>, query: GraphQLQuery) -> GraphQLResponse {
    query.execute(schema.inner()).await
}

#[post("/graphql", data = "<request>", format = "application/json")]
async fn graphql_request(schema: &State<AppSchema>, request: GraphQLRequest) -> GraphQLResponse {
    request.execute(schema.inner()).await
}

#[get("/graphiql")]
fn graphiql() -> rocket::response::content::RawHtml<String> {
    rocket::response::content::RawHtml(async_graphql::http::graphiql_source(
        "/graphql",
        None,
    ))
}

#[launch]
async fn rocket() -> _ {
    dotenvy::dotenv().ok();

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    let config = config::AppConfig::from_env().expect("Failed to load configuration");

    let db = db::connect(&config.database_url)
        .await
        .expect("Failed to connect to database");

    migration::Migrator::up(&db, None)
        .await
        .expect("Failed to run migrations");

    let firebase_client = Arc::new(
        firebase::FirebaseClient::from_json_file(&config.firebase_credentials_path)
            .expect("Failed to initialize Firebase client"),
    );

    let schema = Schema::build(
        QueryRoot::default(),
        MutationRoot::default(),
        async_graphql::EmptySubscription,
    )
    .data(db)
    .data(firebase_client)
    .finish();

    rocket::build()
        .manage(schema)
        .mount("/", routes![graphql_query, graphql_request, graphiql])
}
