package com.example.restaurantassistant.model;

import java.math.BigDecimal;
import java.util.List;

public record MenuItem(String id,String name,String category,String description,BigDecimal price,List<String> ingredients,List<String> allergens,boolean vegetarian,boolean vegan,int spiceLevel) {
}
