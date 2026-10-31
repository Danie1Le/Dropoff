# Foreign keys across modules until the service split

While Dropoff is one app, foreign keys exist everywhere, including across module boundaries (e.g. `orders.merchant_id → merchants.id`), so Postgres rejects orphaned or dangling rows. When the app splits into services, the cross-module foreign keys are dropped deliberately; how integrity is kept after that is decided with the service-split design (wayfinder ticket "Service split: data ownership and cross-service price lookup", #16).
