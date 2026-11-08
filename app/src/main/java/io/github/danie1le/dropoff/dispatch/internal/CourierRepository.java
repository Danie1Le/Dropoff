package io.github.danie1le.dropoff.dispatch.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CourierRepository extends JpaRepository<Courier, Long> {

    /**
     * The courier, locked with {@code SELECT … FOR UPDATE} until the transaction ends. Status and location changes
     * read and write the row under this lock, so they never overwrite a change made by another request in between.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Courier c WHERE c.id = :id")
    Optional<Courier> findByIdForUpdate(long id);
}
