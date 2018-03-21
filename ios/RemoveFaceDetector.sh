#!/bin/sh

#  RemoveFaceDetectorFromRNCamera.sh
#  MyFoodRepoApp
#
#  Created by Boris Conforty on 16.03.18.
#  Copyright © 2018 Facebook. All rights reserved.

# This script makes compilation fail the first time right after
# react-native-camera is added
# Just compile again and it should be fine

project="$SRCROOT/RNCamera.xcodeproj/project.pbxproj"
for file in RNFaceDetectorManager RNFaceDetectorModule RNFaceDetectorPointTransformCalculator RNFaceDetectorUtils RNFaceEncoder
do
  grep "$file\.[hm]" "$project" && sed -i '' "/$file\.[hm]/d" "$project"
done

exit 0
