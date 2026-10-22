package com.example.restaurantassistant.repository;

import com.example.restaurantassistant.entity.UserPreference;
import com.example.restaurantassistant.model.PreferenceStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
  List<UserPreference> findByUserIdAndStatusOrderByIdAsc(Long userId, PreferenceStatus status);
}
