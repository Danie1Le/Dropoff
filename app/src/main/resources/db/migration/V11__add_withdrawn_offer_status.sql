-- WITHDRAWN: dispatch closed an open offer that its courier never answered (ticket #85). It happens when a late
-- OrderPickedUp gives the order back to the courier who already carries it, after going OFFLINE had re-dispatched it.
ALTER TABLE offers DROP CONSTRAINT offers_status_check;
ALTER TABLE offers ADD CONSTRAINT offers_status_check
    CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'TIMED_OUT', 'WITHDRAWN'));
