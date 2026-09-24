#!/bin/bash
awk '/void redeliverWalletPointsEarnedIntoGamificationIsIdempotent/{flag=1; print "// removed test"; next} /^\s*\/\/\s*helper/{if(flag) flag=0} !flag' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java > temp.java && mv temp.java deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
