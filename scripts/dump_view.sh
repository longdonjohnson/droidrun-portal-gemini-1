#!/bin/bash

# Command options
MODE="clickable"  # Default mode - only clickable elements
OUTPUT_FILE="elements.json"

# Parse command line options
while getopts "am:" opt; do
  case $opt in
    a)
      MODE="all"
      OUTPUT_FILE="all_elements.json"
      ;;
    m)
      MODE=$OPTARG
      if [ "$MODE" = "all" ]; then
        OUTPUT_FILE="all_elements.json"
      else
        OUTPUT_FILE="elements.json"
      fi
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      echo "Usage: $0 [-a] [-m mode]" >&2
      echo "  -a         Get all elements (shorthand for -m all)" >&2
      echo "  -m MODE    Mode: 'clickable' (default) or 'all'" >&2
      exit 1
      ;;
  esac
done

echo "📱 Droidrun Portal - Running in $MODE mode. Output will be saved to $OUTPUT_FILE"

# Clear logcat first
adb logcat -c

# Increase logcat buffer size
adb logcat -G 16M

# Send broadcast to request elements
if [ "$MODE" = "all" ]; then
  echo "🔍 Requesting ALL elements..."
  adb shell am broadcast -a com.droidrun.portal.GET_ALL_ELEMENTS
else
  echo "🔍 Requesting clickable elements..."
  adb shell am broadcast -a com.droidrun.portal.GET_ELEMENTS
fi

# Wait for data collection
echo "⏳ Waiting for response..."
sleep 3

# Retrieve logs based on mode
LOG_TAG="DROIDRUN_ADB_DATA"
if [ "$MODE" = "all" ]; then
  LOG_TAG="DROIDRUN_ADB_ALL_DATA"
fi

# Process chunks and reconstruct the JSON
echo "🔄 Processing data..."
echo "" > temp.json
CHUNKS=$(adb logcat -d | grep "$LOG_TAG" | grep "CHUNK|")

if [ -z "$CHUNKS" ]; then
  echo "❌ No element data found in logs. Make sure the service is running correctly."
  # Try to find any relevant error messages
  adb logcat -d | grep -E "DROIDRUN_ADB_RESPONSE|DROIDRUN_RECEIVER|DROIDRUN_ERROR" | tail -5
  rm temp.json
  exit 1
fi

# Extract and combine chunks
echo "$CHUNKS" | while IFS= read -r line; do
  if [[ $line =~ CHUNK\|([0-9]+)\|([0-9]+)\|(.*) ]]; then
    chunk_index="${BASH_REMATCH[1]}"
    total_chunks="${BASH_REMATCH[2]}"
    chunk_data="${BASH_REMATCH[3]}"
    echo "$chunk_data" >> temp.json
    echo -ne "   Processing chunk $((chunk_index+1))/$total_chunks\r"
  fi
done
echo ""

# Check file data
if [ ! -s temp.json ]; then
  echo "❌ Failed to reconstruct JSON data. The temporary file is empty."
  # Check if there's any data from the file approach
  JSON_PATH=$(adb shell logcat -d | grep "DROIDRUN_FILE" | grep "JSON data written to" | tail -1 | sed 's/.*JSON data written to: \(.*\)/\1/')
  
  if [ -n "$JSON_PATH" ]; then
    echo "⚠️ Trying alternative method - pulling JSON file directly..."
    adb pull "$JSON_PATH" "$OUTPUT_FILE"
    if [ $? -eq 0 ]; then
      echo "✅ Successfully retrieved JSON data from file: $JSON_PATH"
      echo "💾 Data saved to $OUTPUT_FILE"
      rm temp.json
      exit 0
    fi
  fi
  
  echo "❌ All methods failed. Unable to retrieve element data."
  rm temp.json
  exit 1
fi

# Sanitize JSON data (remove control characters)
echo "🧹 Sanitizing JSON data..."
cat temp.json | tr -d '\000-\037' > temp_clean.json
mv temp_clean.json temp.json

# Try to format with jq and save to output file
if command -v jq &> /dev/null; then
    if cat temp.json | jq . &> /dev/null; then
        cat temp.json | jq . > "$OUTPUT_FILE"
        echo "✅ JSON data saved to $OUTPUT_FILE"
        ELEMENT_COUNT=$(cat temp.json | jq '. | length')
        if [ -n "$ELEMENT_COUNT" ]; then
            echo "🧩 Element count: $ELEMENT_COUNT"
        else
            echo "⚠️ Could not determine element count"
        fi
    else
        echo "⚠️ JSON validation failed, saving raw output"
        cat temp.json > "$OUTPUT_FILE"
        
        # Try to extract element count from the raw data
        ELEMENT_COUNT=$(grep -o {\\"text\\" temp.json | wc -l)
        if [ "$ELEMENT_COUNT" -gt 0 ]; then
            echo "🧩 Estimated element count: $ELEMENT_COUNT"
        fi
    fi
else
    echo "⚠️ JQ not installed. Saving raw output."
    cat temp.json > "$OUTPUT_FILE"
fi

# Display data info
if [ -f "$OUTPUT_FILE" ]; then
    FILE_SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)
    echo "📊 File size: $FILE_SIZE"
    
    # Try to show content preview
    if command -v jq &> /dev/null && cat "$OUTPUT_FILE" | jq . &> /dev/null; then
        # Get number of elements safely
        TOTAL=$(cat "$OUTPUT_FILE" | jq '. | length')
        if [ -n "$TOTAL" ] && [ "$TOTAL" -gt 0 ]; then
            if [ "$MODE" = "all" ]; then
                echo "📋 Preview of one element (showing all attributes):"
                cat "$OUTPUT_FILE" | jq '.[0]'
                if [ "$TOTAL" -gt 1 ]; then
                    echo "... and $((TOTAL-1)) more elements"
                fi
            else
                echo "📋 Preview of elements (first 3):"
                cat "$OUTPUT_FILE" | jq '.[0:3]'
                if [ "$TOTAL" -gt 3 ]; then
                    echo "... and $((TOTAL-3)) more elements"
                fi
            fi
        else
            echo "⚠️ No elements found in output"
        fi
    else
        echo "📋 Raw content preview (first 200 chars):"
        head -c 200 "$OUTPUT_FILE"
        echo "..."
    fi
else
    echo "⚠️ Output file was not created"
fi

# Cleanup
rm temp.json

echo "✅ Done! Complete data saved to $OUTPUT_FILE" 