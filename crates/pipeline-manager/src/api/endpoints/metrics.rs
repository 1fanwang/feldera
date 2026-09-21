use crate::api::main::ServerState;
use crate::db::types::combined_status::{CombinedDesiredStatus, CombinedStatus};
use crate::db::types::pipeline::ExtendedPipelineDescrMonitoring;
use crate::db::{storage::Storage as _, types::tenant::TenantId};
use crate::error::ManagerError;
use actix_web::{
    HttpResponse, get,
    http::Method,
    web::{Data as WebData, ReqData},
};
use awc::body::MessageBody as _;
use feldera_types::runtime_status::{RuntimeDesiredStatus, RuntimeStatus};
use std::fmt::Write as _;

const STATUS_HEADER: &str = "# HELP pipeline_status Status of the pipeline: 1 for the status it is in, 0 for the others.\n# TYPE pipeline_status gauge\n";
const DESIRED_STATUS_HEADER: &str = "# HELP pipeline_desired_status Status the pipeline was asked to reach: 1 for that status, 0 for the others.\n# TYPE pipeline_desired_status gauge\n";

/// Appends the status and the desired status of `pipeline` to their respective metric families.
fn write_status_metrics(
    status_family: &mut String,
    desired_status_family: &mut String,
    pipeline: &ExtendedPipelineDescrMonitoring,
) {
    let labels = format!(
        "pipeline=\"pipeline-{}\",pipeline_name=\"{}\"",
        pipeline.id, pipeline.name
    );

    let status = CombinedStatus::new(
        pipeline.deployment_resources_status,
        pipeline.deployment_runtime_status,
    );
    for candidate in CombinedStatus::ALL {
        writeln!(
            status_family,
            "pipeline_status{{{labels},status=\"{}\"}} {}",
            candidate.as_str(),
            u8::from(candidate == status)
        )
        .unwrap();
    }

    // `CombinedDesiredStatus` has no counterpart for `Coordination` and panics on it.
    let is_expressible =
        |desired: &RuntimeDesiredStatus| *desired != RuntimeDesiredStatus::Coordination;
    let desired_status = CombinedDesiredStatus::new(
        pipeline.deployment_resources_desired_status,
        pipeline.deployment_initial.filter(is_expressible),
        pipeline
            .deployment_runtime_desired_status
            .filter(is_expressible),
    );
    for candidate in CombinedDesiredStatus::ALL {
        writeln!(
            desired_status_family,
            "pipeline_desired_status{{{labels},status=\"{}\"}} {}",
            candidate.as_str(),
            u8::from(candidate == desired_status)
        )
        .unwrap();
    }
}

/// List All Metrics
///
/// Retrieve the metrics of all pipelines belonging to this tenant.
///
/// The metrics are collected by making individual HTTP requests to `/metrics`
/// endpoint of each pipeline, of which only successful responses are included
/// in the returned list.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    responses(
        (status = OK
        , description = "Metrics of all pipelines belonging to this tenant in Prometheus format"
        , content_type = "text/plain"
        , body = Vec<u8>),
    ),
    tag = "Metrics & Debugging"
)]
#[get("/metrics")]
pub(crate) async fn get_metrics(
    state: WebData<ServerState>,
    client: WebData<awc::Client>,
    tenant_id: ReqData<TenantId>,
) -> Result<HttpResponse, ManagerError> {
    let pipelines = state
        .db
        .lock()
        .await
        .list_pipelines_for_monitoring(*tenant_id)
        .await?;

    const NEWLINE: u8 = b'\n';
    let mut status_family = String::from(STATUS_HEADER);
    let mut desired_status_family = String::from(DESIRED_STATUS_HEADER);
    for pipeline in &pipelines {
        write_status_metrics(&mut status_family, &mut desired_status_family, pipeline);
    }

    let mut result = status_family.into_bytes();
    result.extend(desired_status_family.into_bytes());

    for pipeline in pipelines {
        if (pipeline.deployment_runtime_status == Some(RuntimeStatus::Running)
            || pipeline.deployment_runtime_status == Some(RuntimeStatus::Paused))
            && let Ok(res) = state
                .runner
                .forward_http_request_to_pipeline_by_name(
                    client.as_ref(),
                    *tenant_id,
                    &pipeline.name,
                    Method::GET,
                    "metrics",
                    "",
                    None,
                    None,
                )
                .await
            && res.status().is_success()
            && let Ok(bytes) = res.into_body().try_into_bytes()
        {
            result.extend(bytes);
            result.push(NEWLINE);
        }
    }

    Ok(HttpResponse::Ok().content_type("text/plain").body(result))
}

#[cfg(test)]
mod test {
    use super::{DESIRED_STATUS_HEADER, STATUS_HEADER, write_status_metrics};
    use crate::db::types::combined_status::{CombinedDesiredStatus, CombinedStatus};
    use crate::db::types::pipeline::{ExtendedPipelineDescrMonitoring, PipelineId};
    use crate::db::types::program::ProgramStatus;
    use crate::db::types::resources_status::{ResourcesDesiredStatus, ResourcesStatus};
    use crate::db::types::storage::StorageStatus;
    use crate::db::types::version::Version;
    use feldera_types::runtime_status::{RuntimeDesiredStatus, RuntimeStatus};
    use uuid::Uuid;

    fn stopped_pipeline(name: &str) -> ExtendedPipelineDescrMonitoring {
        ExtendedPipelineDescrMonitoring {
            id: PipelineId(Uuid::from_u128(1)),
            name: name.to_string(),
            description: Default::default(),
            tags: Default::default(),
            created_at: Default::default(),
            version: Version(1),
            platform_version: String::new(),
            runtime_config: Default::default(),
            program_config: Default::default(),
            program_version: Version(1),
            program_status: ProgramStatus::Pending,
            program_status_since: Default::default(),
            deployment_error: None,
            deployment_location: None,
            refresh_version: Version(1),
            storage_status: StorageStatus::Cleared,
            storage_status_details: None,
            deployment_id: None,
            deployment_initial: None,
            deployment_resources_status: ResourcesStatus::Stopped,
            deployment_resources_status_since: Default::default(),
            deployment_resources_desired_status: ResourcesDesiredStatus::Stopped,
            deployment_resources_desired_status_since: Default::default(),
            deployment_runtime_status: None,
            deployment_runtime_status_details: None,
            deployment_runtime_status_since: None,
            deployment_runtime_desired_status: None,
            bootstrap_policy: None,
            deployment_runtime_desired_status_since: None,
        }
    }

    fn families(pipeline: &ExtendedPipelineDescrMonitoring) -> (String, String) {
        let mut status_family = String::new();
        let mut desired_status_family = String::new();
        write_status_metrics(&mut status_family, &mut desired_status_family, pipeline);
        (status_family, desired_status_family)
    }

    /// Returns the status whose line is the only one valued 1.
    fn reported(family: &str) -> String {
        let ones: Vec<&str> = family.lines().filter(|line| line.ends_with(" 1")).collect();
        assert_eq!(ones.len(), 1, "expected exactly one status in {family}");
        ones[0]
            .split_once("status=\"")
            .unwrap()
            .1
            .split_once('"')
            .unwrap()
            .0
            .to_string()
    }

    #[test]
    fn stopped_pipeline_reports_all_statuses() {
        let (status_family, desired_status_family) = families(&stopped_pipeline("p1"));

        assert_eq!(
            status_family,
            r#"pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Stopped"} 1
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Provisioning"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Unavailable"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Coordination"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Standby"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="AwaitingApproval"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Initializing"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Bootstrapping"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="ConcurrentBootstrapping"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Synchronizing"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Replaying"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Paused"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Running"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Suspended"} 0
pipeline_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Stopping"} 0
"#
        );
        assert_eq!(
            desired_status_family,
            r#"pipeline_desired_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Stopped"} 1
pipeline_desired_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Unavailable"} 0
pipeline_desired_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Standby"} 0
pipeline_desired_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Paused"} 0
pipeline_desired_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Running"} 0
pipeline_desired_status{pipeline="pipeline-00000000-0000-0000-0000-000000000001",pipeline_name="p1",status="Suspended"} 0
"#
        );
    }

    #[test]
    fn pipelines_share_the_two_families() {
        let mut status_family = String::new();
        let mut desired_status_family = String::new();
        for name in ["p1", "p2"] {
            write_status_metrics(
                &mut status_family,
                &mut desired_status_family,
                &stopped_pipeline(name),
            );
        }

        for name in ["p1", "p2"] {
            let label = format!("pipeline_name=\"{name}\"");
            assert_eq!(
                status_family.matches(&label).count(),
                CombinedStatus::ALL.len()
            );
            assert_eq!(
                desired_status_family.matches(&label).count(),
                CombinedDesiredStatus::ALL.len()
            );
        }
        assert!(
            status_family
                .lines()
                .all(|line| line.starts_with("pipeline_status{"))
        );
        assert!(
            desired_status_family
                .lines()
                .all(|line| line.starts_with("pipeline_desired_status{"))
        );
    }

    #[test]
    fn headers_name_their_family() {
        assert!(STATUS_HEADER.contains("# TYPE pipeline_status gauge\n"));
        assert!(DESIRED_STATUS_HEADER.contains("# TYPE pipeline_desired_status gauge\n"));
    }

    #[test]
    fn each_status_is_reported_in_turn() {
        use ResourcesStatus::*;
        use RuntimeStatus::*;

        let cases = [
            (Stopped, None, "Stopped"),
            (Provisioning, None, "Provisioning"),
            (Stopping, None, "Stopping"),
            (Provisioned, Some(Unavailable), "Unavailable"),
            (Provisioned, Some(Coordination), "Coordination"),
            (Provisioned, Some(Standby), "Standby"),
            (Provisioned, Some(AwaitingApproval), "AwaitingApproval"),
            (Provisioned, Some(Initializing), "Initializing"),
            (Provisioned, Some(Bootstrapping), "Bootstrapping"),
            (
                Provisioned,
                Some(ConcurrentBootstrapping),
                "ConcurrentBootstrapping",
            ),
            (Provisioned, Some(Synchronizing), "Synchronizing"),
            (Provisioned, Some(Replaying), "Replaying"),
            (Provisioned, Some(Paused), "Paused"),
            (Provisioned, Some(Running), "Running"),
            (Provisioned, Some(Suspended), "Suspended"),
        ];
        assert_eq!(cases.len(), CombinedStatus::ALL.len());

        for (resources_status, runtime_status, expected) in cases {
            let mut pipeline = stopped_pipeline("p1");
            pipeline.deployment_resources_status = resources_status;
            pipeline.deployment_runtime_status = runtime_status;

            let (status_family, _) = families(&pipeline);
            assert_eq!(reported(&status_family), expected);
            assert_eq!(status_family.lines().count(), CombinedStatus::ALL.len());
        }
    }

    #[test]
    fn each_desired_status_is_reported_in_turn() {
        use ResourcesDesiredStatus::*;
        use RuntimeDesiredStatus::*;

        let cases = [
            (Stopped, None, "Stopped"),
            (Provisioned, Some(Unavailable), "Unavailable"),
            (Provisioned, Some(Standby), "Standby"),
            (Provisioned, Some(Paused), "Paused"),
            (Provisioned, Some(Running), "Running"),
            (Provisioned, Some(Suspended), "Suspended"),
        ];
        assert_eq!(cases.len(), CombinedDesiredStatus::ALL.len());

        for (resources_desired, runtime_desired, expected) in cases {
            let mut pipeline = stopped_pipeline("p1");
            pipeline.deployment_resources_desired_status = resources_desired;
            pipeline.deployment_runtime_desired_status = runtime_desired;

            let (_, desired_status_family) = families(&pipeline);
            assert_eq!(reported(&desired_status_family), expected);
            assert_eq!(
                desired_status_family.lines().count(),
                CombinedDesiredStatus::ALL.len()
            );
        }
    }

    #[test]
    fn desired_status_falls_back_to_the_initial_one() {
        let mut pipeline = stopped_pipeline("p1");
        pipeline.deployment_resources_desired_status = ResourcesDesiredStatus::Provisioned;
        pipeline.deployment_initial = Some(RuntimeDesiredStatus::Paused);

        let (_, desired_status_family) = families(&pipeline);
        assert_eq!(reported(&desired_status_family), "Paused");
    }

    #[test]
    fn statuses_without_a_reportable_value_become_unavailable() {
        let mut pipeline = stopped_pipeline("p1");
        pipeline.deployment_resources_status = ResourcesStatus::Provisioned;
        pipeline.deployment_runtime_status = None;
        pipeline.deployment_resources_desired_status = ResourcesDesiredStatus::Provisioned;
        pipeline.deployment_initial = Some(RuntimeDesiredStatus::Coordination);
        pipeline.deployment_runtime_desired_status = Some(RuntimeDesiredStatus::Coordination);

        let (status_family, desired_status_family) = families(&pipeline);
        assert_eq!(reported(&status_family), "Unavailable");
        assert_eq!(reported(&desired_status_family), "Unavailable");
    }
}
