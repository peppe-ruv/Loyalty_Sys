#!/bin/bash
sed -i 's/private long count/public long count/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
