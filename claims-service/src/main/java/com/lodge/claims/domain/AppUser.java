package com.lodge.claims.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id private UUID id;
    private String name;
    private String email;
    private String password;
    @Enumerated(EnumType.STRING) private Role role;
    @Enumerated(EnumType.STRING) private Market market;

    protected AppUser() {}

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public Role getRole() { return role; }
    public Market getMarket() { return market; }
}
