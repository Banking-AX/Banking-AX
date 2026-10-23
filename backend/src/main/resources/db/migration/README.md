# Flyway migration ownership

The canonical schema is `docs/01_schema_mysql8.sql`. This repository has no
applied Flyway history yet, so no copied `V1` migration is committed in Phase 0.
T1 will create the initial immutable migration only after the team confirms the
target MySQL database is empty and its merge order. This avoids silently
diverging a copied schema from the DDL authority.

Do not edit an applied migration. Do not allocate version ranges by developer.
