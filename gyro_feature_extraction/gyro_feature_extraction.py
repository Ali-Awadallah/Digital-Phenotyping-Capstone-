import pandas as pd
import numpy as np

# === CONFIG ===
INPUT_FILE = "2022-03-21 01_00_00+00_00.csv"
OUTPUT_FILE = "gyro_features_labeled.csv"
WINDOW = '1S'  # you can change to '10S', '30S', etc.

# === LOAD DATA ===
df = pd.read_csv(INPUT_FILE)

# Convert timestamp
df['timestamp'] = df['timestamp'].astype(float)
df['datetime'] = pd.to_datetime(df['timestamp'], unit='ms')

# Calculate rotation magnitude
df['gyro_mag'] = np.sqrt(df['x']**2 + df['y']**2 + df['z']**2)

# Set index for resampling
df.set_index('datetime', inplace=True)

# Aggregate features
window = df.resample(WINDOW).agg({
    'gyro_mag': ['mean', 'std', 'max']
})

window.columns = ['mean_gyro', 'std_gyro', 'max_gyro']

# === Classification logic ===
def classify_rotation(row):
    if row['max_gyro'] > 2.0:
        return "high_rotation"
    if row['mean_gyro'] > 1.5:
        return "high_rotation"
    elif row['mean_gyro'] > 0.5:
        return "phone_handling"
    elif row['mean_gyro'] > 0.1:
        return "slight_rotation"
    else:
        return "no_rotation"

window['rotation_label'] = window.apply(classify_rotation, axis=1)

# Reset index and format datetime
window = window.reset_index()
window['datetime'] = window['datetime'].dt.strftime('%Y-%m-%d %H:%M:%S')

# Save
window.to_csv(OUTPUT_FILE, index=False)

print(f"Saved {len(window)} rows to {OUTPUT_FILE}")
print(window.head(10))
