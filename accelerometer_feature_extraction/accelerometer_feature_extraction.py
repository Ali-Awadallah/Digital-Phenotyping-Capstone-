import pandas as pd
import numpy as np

# === CONFIG ===
INPUT_FILE = "2022-03-21 13_00_00+00_00.csv"
OUTPUT_FILE = "accelerometer_features_labeled.csv"

# Window size:
#   '1min'  -> 1 minute
#   '30S'   -> 30 seconds
#   '10S'   -> 10 seconds
WINDOW = '1min'   # change this if you want more rows

# === LOAD DATA ===
df = pd.read_csv(INPUT_FILE)

# Make sure timestamp is float and convert to datetime
df['timestamp'] = df['timestamp'].astype(float)
df['datetime'] = pd.to_datetime(df['timestamp'], unit='ms')

# Compute magnitude
df['magnitude'] = np.sqrt(df['x']**2 + df['y']**2 + df['z']**2)

# Use datetime as index for resampling
df.set_index('datetime', inplace=True)

# Aggregate per time window
window = df.resample(WINDOW).agg({
    'magnitude': ['mean', 'std', 'max']
})

# Clean column names
window.columns = ['mean_mag', 'std_mag', 'max_mag']

# === Activity labeling ===
def classify_activity(row):
    # Using both mean and max now
    if row['max_mag'] > 1.8:
        return "running"
    elif row['max_mag'] > 1.4:
        return "walking"
    elif row['mean_mag'] > 1.05:
        return "light_movement"
    else:
        return "resting"

window['activity_label'] = window.apply(classify_activity, axis=1)

# Move index (datetime) into a normal column and format it nicely
window = window.reset_index()      # 'datetime' becomes a column
window['datetime'] = window['datetime'].dt.strftime('%Y-%m-%d %H:%M:%S')

# Optional: rename column for clarity
window = window.rename(columns={'datetime': 'time_window_start'})

# Save to CSV WITHOUT index
window.to_csv(OUTPUT_FILE, index=False)

print(f"Saved {len(window)} rows to {OUTPUT_FILE}")
print(window.head(10))
