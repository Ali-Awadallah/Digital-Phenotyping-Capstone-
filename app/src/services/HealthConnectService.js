/**
 * Health Connect Service
 * 
 * Wrapper for the native HealthConnectModule to read wearable data
 * from Android Health Connect. This feature is optional - not all
 * participants will have wearables or Health Connect apps installed.
 * 
 * Supported data types:
 * - Heart Rate (from smartwatches, fitness trackers)
 * - Steps (from phones, smartwatches)
 * - Sleep Sessions (from sleep tracking apps/wearables)
 * - Blood Pressure (from blood pressure monitors)
 * - Weight (from smart scales)
 * - Oxygen Saturation (from pulse oximeters, smartwatches)
 * - Respiratory Rate (from wearables that support it)
 */

import { NativeModules, Platform } from 'react-native';

const { HealthConnectModule } = NativeModules;

/**
 * Check if Health Connect is available on this device
 * @returns {Promise<boolean>} true if Health Connect is available
 */
export async function isAvailable() {
    if (Platform.OS !== 'android') {
        return false;
    }

    try {
        return await HealthConnectModule.isAvailable();
    } catch (error) {
        console.log('Health Connect availability check failed:', error);
        return false;
    }
}

/**
 * Open Health Connect settings for the user to grant permissions
 * @returns {Promise<boolean>} true if settings were opened successfully
 */
export async function openSettings() {
    if (Platform.OS !== 'android') {
        return false;
    }

    try {
        return await HealthConnectModule.openHealthConnectSettings();
    } catch (error) {
        console.log('Failed to open Health Connect settings:', error);
        return false;
    }
}

/**
 * Check if all required permissions are granted
 * @returns {Promise<boolean>} true if all permissions are granted
 */
export async function checkPermissions() {
    if (Platform.OS !== 'android') {
        return false;
    }

    try {
        return await HealthConnectModule.checkPermissions();
    } catch (error) {
        console.log('Failed to check Health Connect permissions:', error);
        return false;
    }
}

/**
 * Request Health Connect permissions from the user
 * This will open the Health Connect permission dialog
 * @returns {Promise<boolean>} true if all permissions were granted
 */
export async function requestPermissions() {
    if (Platform.OS !== 'android') {
        return false;
    }

    try {
        return await HealthConnectModule.requestPermissions();
    } catch (error) {
        console.log('Failed to request Health Connect permissions:', error);
        return false;
    }
}

/**
 * Get detailed permission status for each health metric
 * @returns {Promise<Object>} Object with boolean for each metric
 */
export async function getPermissionStatus() {
    if (Platform.OS !== 'android') {
        return {};
    }

    try {
        return await HealthConnectModule.getPermissionStatus();
    } catch (error) {
        console.log('Failed to get permission status:', error);
        return {};
    }
}

/**
 * Get heart rate records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{timestamp: number, bpm: number}>>} Heart rate samples
 */
export async function getHeartRate(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getHeartRateRecords(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get heart rate records:', error);
        return [];
    }
}

/**
 * Get step count records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{startTime: number, endTime: number, count: number}>>} Step records
 */
export async function getSteps(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getStepsRecords(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get steps records:', error);
        return [];
    }
}

/**
 * Get sleep session records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{startTime: number, endTime: number, title: string, notes: string}>>} Sleep sessions
 */
export async function getSleepSessions(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getSleepSessions(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get sleep sessions:', error);
        return [];
    }
}

/**
 * Get blood pressure records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{timestamp: number, systolic: number, diastolic: number}>>} Blood pressure records
 */
export async function getBloodPressure(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getBloodPressureRecords(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get blood pressure records:', error);
        return [];
    }
}

/**
 * Get weight records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{timestamp: number, weightKg: number}>>} Weight records
 */
export async function getWeight(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getWeightRecords(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get weight records:', error);
        return [];
    }
}

/**
 * Get oxygen saturation records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{timestamp: number, percentage: number}>>} SpO2 records
 */
export async function getOxygenSaturation(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getOxygenSaturationRecords(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get oxygen saturation records:', error);
        return [];
    }
}

/**
 * Get respiratory rate records for a time range
 * @param {Date} startTime - Start of time range
 * @param {Date} endTime - End of time range
 * @returns {Promise<Array<{timestamp: number, rate: number}>>} Respiratory rate records
 */
export async function getRespiratoryRate(startTime, endTime) {
    if (Platform.OS !== 'android') {
        return [];
    }

    try {
        return await HealthConnectModule.getRespiratoryRateRecords(
            startTime.getTime(),
            endTime.getTime()
        );
    } catch (error) {
        console.log('Failed to get respiratory rate records:', error);
        return [];
    }
}

/**
 * Get all available health data for the last 24 hours
 * Useful for a quick overview of wearable data
 * @returns {Promise<Object>} Object containing all health data types
 */
export async function getLast24HoursData() {
    const endTime = new Date();
    const startTime = new Date(endTime.getTime() - 24 * 60 * 60 * 1000);

    const [
        heartRate,
        steps,
        sleep,
        bloodPressure,
        weight,
        oxygenSaturation,
        respiratoryRate
    ] = await Promise.all([
        getHeartRate(startTime, endTime),
        getSteps(startTime, endTime),
        getSleepSessions(startTime, endTime),
        getBloodPressure(startTime, endTime),
        getWeight(startTime, endTime),
        getOxygenSaturation(startTime, endTime),
        getRespiratoryRate(startTime, endTime)
    ]);

    return {
        heartRate,
        steps,
        sleep,
        bloodPressure,
        weight,
        oxygenSaturation,
        respiratoryRate,
        fetchedAt: endTime.toISOString()
    };
}

/**
 * Manually sync the last 24 hours of wearable data to the backend server
 * @returns {Promise<string>} Summary message of records synced
 */
export async function syncToBackend() {
    return await HealthConnectModule.syncToBackend();
}

export default {
    isAvailable,
    openSettings,
    checkPermissions,
    requestPermissions,
    getPermissionStatus,
    getHeartRate,
    getSteps,
    getSleepSessions,
    getBloodPressure,
    getWeight,
    getOxygenSaturation,
    getRespiratoryRate,
    getLast24HoursData,
    syncToBackend
};
