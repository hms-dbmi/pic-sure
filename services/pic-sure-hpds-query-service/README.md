# pic-sure-hpds-query-service

DB-free single HPDS ingress (Phase 4) + open-path aggregate obfuscation (Phase 5).

## Rollback (aggregate fold — Phase 5)
The legacy `pic-sure-aggregate-data-sharing-resource` WAR remains deployed on WildFly through Phase 6 and is
removed only in Phase 7. The open-path obfuscation now runs here (`/hpds/open[/v3]/query/sync`). To revert to
the WAR (BDC/prod only — the AIO does not deploy the aggregate WAR): repoint the gateway's `/hpds/open/**`
route back to WildFly's aggregate WAR context and redeploy. This service's aggregate endpoints keep running
but receive no traffic. (In single-HPDS AIO there is nothing to roll back to.)
