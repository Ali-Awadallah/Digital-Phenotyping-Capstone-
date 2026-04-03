/**
 * BackgroundService.js - JavaScript wrapper for the native BackgroundService module.
 * 
 * Provides a clean API for controlling the background sensor collection service.
 */
import { NativeModules, Platform } from 'react-native';

const { BackgroundService } = NativeModules;

/**
 * Check if the background service module is available (Android only)
 */
export const isAvailable = () => {
    return Platform.OS === 'android' && BackgroundService != null;
};

/**
 * Start the background sensor collection service.
 * Creates a persistent notification and begins collecting sensor data.
 * @returns {Promise<boolean>} true if started successfully
 */
export const startBackgroundCollection = async () => {
    if (!isAvailable()) {
        console.warn('BackgroundService: Not available on this platform');
        return false;
    }
    return await BackgroundService.startService();
};

/**
 * Stop the background sensor collection service.
 * Removes the notification and stops all sensor collection.
 * @returns {Promise<boolean>} true if stopped successfully
 */
export const stopBackgroundCollection = async () => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.stopService();
};

/**
 * Check if the background service is currently running.
 * @returns {Promise<boolean>} true if service is running
 */
export const isServiceRunning = async () => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.isServiceRunning();
};

/**
 * Get collected sensor data from the background service.
 * @param {string} sensorType - 'location', 'accelerometer', 'gyroscope', or 'pedometer'
 * @returns {Promise<Array>} Array of sensor readings (last 100 entries)
 */
export const getCollectedData = async (sensorType) => {
    if (!isAvailable()) {
        return [];
    }
    return await BackgroundService.getCollectedData(sensorType);
};

/**
 * Clear collected sensor data.
 * @param {string} sensorType - 'location', 'accelerometer', 'gyroscope', 'pedometer', or 'all'
 * @returns {Promise<boolean>} true if cleared successfully
 */
export const clearCollectedData = async (sensorType = 'all') => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.clearCollectedData(sensorType);
};

/**
 * Set whether the service should auto-start on device boot.
 * @param {boolean} enabled - true to enable auto-start
 * @returns {Promise<boolean>} true if preference was set successfully
 */
export const setAutoStartOnBoot = async (enabled) => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.setAutoStartOnBoot(enabled);
};

/**
 * Get status info for each sensor's log file to verify background collection.
 * @returns {Promise<Object>} Object with status for each sensor: { location, accelerometer, gyroscope, pedometer }
 * Each sensor has: { exists, sizeBytes, lastModified, entryCount }
 */
export const getDataStatus = async () => {
    if (!isAvailable()) {
        return null;
    }
    return await BackgroundService.getDataStatus();
};

/**
 * Set the device ID for backend API calls.
 * @param {string} deviceId - Unique device identifier
 * @returns {Promise<boolean>} true if set successfully
 */
export const setDeviceId = async (deviceId) => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.setDeviceId(deviceId);
};

/**
 * Get the current device ID used for backend API calls.
 * @returns {Promise<string>} The device ID
 */
export const getDeviceId = async () => {
    if (!isAvailable()) {
        return null;
    }
    return await BackgroundService.getDeviceId();
};

/**
 * Set the backend API base URL.
 * @param {string} url - Base URL (e.g., 'http://192.168.10.3:8080/api')
 * @returns {Promise<boolean>} true if set successfully
 */
export const setAPIBaseURL = async (url) => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.setAPIBaseURL(url);
};

/**
 * Set ingest API key used by native background sender.
 * @param {string} apiKey
 * @returns {Promise<boolean>} true if set successfully
 */
export const setAPIIngestKey = async (apiKey) => {
    if (!isAvailable()) {
        return false;
    }
    return await BackgroundService.setAPIIngestKey(apiKey);
};

/**
 * Get ingest API key used by native background sender.
 * @returns {Promise<string|null>}
 */
export const getAPIIngestKey = async () => {
    if (!isAvailable()) {
        return null;
    }
    return await BackgroundService.getAPIIngestKey();
};

export default {
    isAvailable,
    startBackgroundCollection,
    stopBackgroundCollection,
    isServiceRunning,
    getCollectedData,
    clearCollectedData,
    setAutoStartOnBoot,
    getDataStatus,
    setDeviceId,
    getDeviceId,
    setAPIBaseURL,
    setAPIIngestKey,
    getAPIIngestKey,
};
