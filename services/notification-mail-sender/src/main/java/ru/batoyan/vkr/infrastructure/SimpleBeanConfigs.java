package ru.batoyan.vkr.infrastructure;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimpleBeanConfigs {

    @Bean
    public ModelMapper modelMapper() {
        return new ModelMapper();
    }

}
