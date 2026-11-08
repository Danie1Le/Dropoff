package io.github.danie1le.dropoff.simulator;

/** One line of a placed order: the client sends only ids and quantities. */
record OrderLine(long menuItemId, int quantity) {}
