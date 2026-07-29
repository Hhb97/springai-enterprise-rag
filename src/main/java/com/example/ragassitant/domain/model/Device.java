package com.example.ragassitant.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Device {

    private Long id;
    private String name;
    private String sn;
    private String location;
    private Integer status;
    private String installTime;
}