package com.example.restaurantassistant.service;

import com.example.restaurantassistant.entity.AppUser;
import com.example.restaurantassistant.repository.AppUserRepository;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
  private final AppUserRepository users;

  public UserService(AppUserRepository users) {
    this.users = users;
  }

  public AppUser require(Long id) {
    return users.findById(id).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
  }

  @Transactional
  public AppUser login(String username) {
    String key = username.toLowerCase(Locale.ROOT);
    return users.findByUsername(key).orElseGet(() -> {
      var user = new AppUser();
      user.username = key;
      return users.saveAndFlush(user);
    });
  }
}
