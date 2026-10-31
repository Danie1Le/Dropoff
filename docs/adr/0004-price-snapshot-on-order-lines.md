# Copy the menu price onto each order line

`order_lines.price_cents` duplicates `menu_items.price_cents` on purpose: it is the price at the moment of purchase, so receipts and totals stay correct after a merchant changes prices. A price-history table (`valid_from`/`valid_to`) was considered but rejected: it makes every receipt lookup a time-range query, while the copy costs 8 bytes per order line.
