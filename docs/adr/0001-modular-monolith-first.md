# Modular monolith first, split into services later

Dropoff starts as one Spring Boot app with a package per domain (`merchant`, `order`, `dispatch`, plus shared `common`), with boundaries checked by Spring Modulith. It splits into separate services when Kafka is introduced (build milestone M5). Starting with three services would mean three databases, network calls, and events before the first order is saved; one app lets the data model settle first, and enforced package boundaries keep the later split mechanical.
