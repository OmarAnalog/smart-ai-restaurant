package com.example.restaurantassistant.repository;
import com.example.restaurantassistant.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {
  Optional<CustomerOrder> findByUserIdAndRequestId(Long userId, UUID requestId);
  List<CustomerOrder> findTop20ByUserIdOrderByIdDesc(Long userId);
}
