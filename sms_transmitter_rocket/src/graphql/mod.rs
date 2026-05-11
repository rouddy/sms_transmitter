use async_graphql::{EmptySubscription, MergedObject, Schema};

pub mod fcm_token;
pub mod sms_request;

use fcm_token::{FcmTokenMutation, FcmTokenQuery};
use sms_request::{SmsRequestMutation, SmsRequestQuery};

#[derive(MergedObject, Default)]
pub struct QueryRoot(FcmTokenQuery, SmsRequestQuery);

#[derive(MergedObject, Default)]
pub struct MutationRoot(FcmTokenMutation, SmsRequestMutation);

pub type AppSchema = Schema<QueryRoot, MutationRoot, EmptySubscription>;
