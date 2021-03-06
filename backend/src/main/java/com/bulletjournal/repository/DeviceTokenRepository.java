package com.bulletjournal.repository;

import com.bulletjournal.repository.models.DeviceToken;
import com.bulletjournal.repository.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    DeviceToken findDeviceTokenByToken(String token);

    List<DeviceToken> findDeviceTokensByUser(User user);
}
