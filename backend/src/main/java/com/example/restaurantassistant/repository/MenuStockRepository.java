package com.example.restaurantassistant.repository;
import com.example.restaurantassistant.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MenuStockRepository extends JpaRepository<MenuStock, String> {}
