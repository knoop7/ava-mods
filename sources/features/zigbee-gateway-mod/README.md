# Zigbee Gateway Mod

A TCP-to-Serial gateway module for [Ava](https://github.com/knoop7/Ava) that bridges Zigbee dongles over TCP.

## Features

- TCP server for multiple client connections
- Serial port communication with configurable baud rate
- Bidirectional data forwarding (TCP ↔ Serial)
- Automatic client connection management
- Statistics tracking (clients, bytes transferred)
- Configurable serial port and TCP settings

## Requirements

- Android device with USB host or serial port access
- Root access for serial port access (typically `/dev/ttyUSB*`)
- Zigbee coordinator dongle (e.g., CC2652, ConBee, etc.)

## Configuration

```java
ZigbeeGatewayManager manager = ZigbeeGatewayManager.getInstance(context);

// Serial settings
manager.setSerialPort("/dev/ttyUSB0");
manager.setBaudrate(115200);
manager.setRtsctsFlow(false);

// TCP settings
manager.setTcpPort(8888);
manager.setListenAddress("0.0.0.0");

// Control
manager.startGateway();
manager.stopGateway();

// Status
boolean running = manager.isServerRunning();
int clients = manager.getClientCount();
long rx = manager.getBytesReceived();
long tx = manager.getBytesSent();
```

## Protocol

- TCP clients connect to configured port
- All received TCP data forwards to serial port
- All serial data broadcasts to all connected TCP clients
- Client connections tracked and cleaned up on disconnect

## Use Cases

- Zigbee2MQTT alternative on Android
- Home Assistant Zigbee integration
- Custom Zigbee automation bridges
- Zigbee network debugging tools

## Build

```bash
cd sources/features/zigbee-gateway-mod
./build.sh
```

Outputs `libs/zigbee-gateway-manager.jar`.

## License

Part of the [ava-mods](https://github.com/knoop7/ava-mods) project.
