/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.configs;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author krash
 */
@Configuration
public class AwsConfig {
    
    @Bean
    public AWSCredentials awsCredentials() {
        ClasspathPropertiesFileCredentialsProvider credentials = new ClasspathPropertiesFileCredentialsProvider("AwsS3Credentials.properties");
        return credentials.getCredentials();
    }

    @Bean
    public AmazonS3Client amazonS3() {
        AmazonS3Client s3 = new AmazonS3Client(awsCredentials());
        return s3;
    }
    
}
