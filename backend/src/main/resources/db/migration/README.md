# Flyway migration ownership

The canonical schema is `docs/01_schema_mysql8.sql`. `V1__core_case_workflow.sql`
is the first immutable, MySQL 8 migration for the currently implemented
Workflow/WorkItem/ServiceCase path. It copies only the parent and Case tables
needed by the JPA adapter, preserving their DDL column types, FK boundaries,
status checks, and unique constraints. The remaining canonical tables need
later additive migrations; do not re-create a table already created by V1.

Do not edit an applied migration. Do not allocate version ranges by developer.
