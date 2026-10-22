package com.example.restaurantassistant.repository;

import com.example.restaurantassistant.entity.AppUser;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
  Optional<AppUser> findByUsername(String username);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AppUser u where u.id = :id")
  Optional<AppUser> lockById(Long id);
}
