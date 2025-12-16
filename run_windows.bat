@echo off
echo 🚚 Building and running Courier Route Optimizer...
mvn clean package exec:java -Dexec.mainClass=com.courier.optimizer.Main
pause
