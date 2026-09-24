#!/bin/bash
sed -i 's/insight.metric_daily/gamification.member_points/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
