# Flyway owns the schema; JPA for CRUD, hand-written SQL for the rest

Every table is created by hand-written Flyway SQL migrations, and Hibernate runs with `ddl-auto=validate`, so it only checks the schema and never changes it. Everyday saves and loads use Spring Data JPA; queries that matter (joins, reports, the courier claim) are hand-written SQL. Letting Hibernate generate the schema is common in tutorials but risky in production, and hand-written SQL keeps the queries visible and reviewable.
