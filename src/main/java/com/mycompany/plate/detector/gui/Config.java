/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mycompany.plate.detector.gui;

/**
 *
 * @author rickyandhi
 */
public class Config {
        
    private final String rabbitmqExchangeName = "plate_number_recognition_system";
    
    public void parseJsonConfigFile(String filePath) {
        
    }

    public String getJdbcConnectionString() {
        return "jdbc:mysql://localhost/plate_detector?user=root&password=password&useLegacyDatetimeCode=false&serverTimezone=Asia/Jakarta";
    }

    public String getRabbitmqHost() {
        return "localhost";
    }
    
    public Integer getRabbitmqPort() {
        return 5672;
    }
    
    public String getRabbitmqExchangeName() {
        return this.rabbitmqExchangeName;
    }
    
    public String getRabbitmqExchangeType() {
        return "topic";
    }
    
    public String getCameraServiceHost() {
        return "localhost";
    }
    
    public Integer getCameraServicePort() {
        return 5000;
    }
    
    public String getDetectedPlateWithFullImageRoutingName() {
        return "plate_number_character_detector_service.detected_plate_number";
    }

    public String getDetectedPlateNumberRoutingKey() {
        return "plate_number_character_detector_service.detected_plate_number";
    }

    public String getViewRtspCameraUrl() {
        return "rtsp://192.168.43.107:8554/mystream";
    }
}
