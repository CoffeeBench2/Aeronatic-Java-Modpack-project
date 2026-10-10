package com.coffeesaerosmp.auth.ticket;

import java.util.UUID;

/** One /ticket row. Pure data — no Minecraft types, so the JSON built from it is unit-testable. */
public record Ticket(long id, UUID uuid, String name, String message, String location,
                     Status status, long createdAt, String closedBy, String lastReply) {

    public enum Status { OPEN, ACK, CLOSED }

    /** A copy with a new status; {@code newClosedBy} null keeps the old one. */
    public Ticket with(Status s, String newClosedBy, String newLastReply) {
        return new Ticket(id, uuid, name, message, location, s, createdAt,
            newClosedBy != null ? newClosedBy : closedBy, newLastReply);
    }
}
