# Dropoff

A three-sided delivery marketplace: customers order food from merchants, and couriers deliver it.

## Marketplace

**Customer**:
A person who places orders and receives deliveries.
_Avoid_: User, buyer, client, account

**Merchant**:
A business that sells menu items and prepares orders.
_Avoid_: Restaurant, store, vendor, seller

**Menu Item**:
One thing a merchant sells at a listed price; the merchant can mark it unavailable.
_Avoid_: Product, dish, SKU

**Menu**:
All the menu items a merchant lists, available or not.
_Avoid_: Catalog, inventory

**Open**:
Whether a merchant accepts new orders right now; closing never affects orders already placed.
_Avoid_: Online, active

**Courier**:
A person who picks up a ready order from a merchant and delivers it to a customer.
_Avoid_: Driver, dasher, rider, shopper

## Ordering

**Order**:
A customer's request to buy one or more menu items from a single merchant.
_Avoid_: Purchase, transaction, cart

**Order Line**:
One menu item on an order, with its quantity and price snapshot.
_Avoid_: Order item, line item, cart item

**Price Snapshot**:
The price of a menu item copied onto an order line at the moment the order is placed; never changes afterward.
_Avoid_: Cached price, current price

**Delivery Address**:
Where an order is delivered: the customer's address copied onto the order when it is placed; never changes afterward.
_Avoid_: Shipping address, destination

**Order Status**:
Where an order is in its lifecycle: Placed, Accepted, Preparing, Ready, Picked Up, Delivered, or Cancelled.
_Avoid_: State, stage, phase

**Cancellation**:
Ending an order before the merchant starts preparing it.
_Avoid_: Refund, void, rejection

## Dispatch

**Dispatch**:
Choosing a courier for a ready order.
_Avoid_: Assignment, matching, routing

**Offer**:
A proposal to one courier to deliver one ready order; the courier accepts or declines within 30 seconds.
_Avoid_: Request, ping, attempt

**Reassignment**:
Dispatching an order again because the courier it was offered to declined or did not respond, or its courier went offline before pickup.
_Avoid_: Redispatch, retry

**Courier Status**:
Whether a courier can take work right now: Offline, Available, or Busy.
_Avoid_: Online flag, online/offline

**Dispatch Radius**:
The distance from a merchant within which dispatch looks for available couriers.
_Avoid_: Delivery radius, range, zone
