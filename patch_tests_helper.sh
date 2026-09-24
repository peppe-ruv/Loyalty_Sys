#!/bin/bash
sed -i 's/public long count/private long count/g' deploy/hub/src/test/java/io/loyaltyhub/hub/HubRedeliveryIT.java
