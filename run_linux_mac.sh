#!/bin/sh
echo "🚚 Quick build and run Courier Route Optimizer..."
mvn clean compile exec:java -Dexec.mainClass=com.courier.optimizer.Main -DskipTests
