package com.ticketmaster.event;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class EventSpecifications {
    public static Specification<Event> matching(String name, EventStatus status, String city, String performer, ZonedDateTime from, ZonedDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (name != null && !name.isBlank())
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%"));

            if (city != null && !city.isBlank()) predicates.add(cb.equal(cb.lower(root.join("venue")
                                                                                      .get("city")),
                                                                         city.toLowerCase()));

            if (status != null) predicates.add(cb.equal(root.get("status"), status));

            if (performer != null && !performer.isBlank())
                predicates.add(cb.like(cb.lower(root.get("performer")), "%" + performer.toLowerCase() + "%"));

            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("startsAt"), from));

            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("startsAt"), to));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}