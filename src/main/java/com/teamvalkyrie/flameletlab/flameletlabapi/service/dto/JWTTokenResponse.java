package com.teamvalkyrie.flameletlab.flameletlabapi.service.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class JWTTokenResponse {
    private String token;
}
