/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mycompany.plate.detector.gui;

import com.google.api.client.util.Key;

/**
 *
 * @author rickyandhi
 */
public class Camera {
    
    @Key
    private Long id;
    
    @Key
    private String cameraName;
    
    @Key
    private String rtspUrl;
    
    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setCameraName(String cameraName) {
        this.cameraName = cameraName;
    }

    public String getCameraName() {
        return cameraName;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }
}
