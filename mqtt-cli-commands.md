# MQTT CLI Commands

## Read One MQTT Message

Use this command to subscribe from the running `mosquitto` container, print one message, and exit:

```powershell
docker compose exec mosquitto mosquitto_sub `
  -h localhost `
  -p 8883 `
  --cafile /mosquitto/certs/ca.crt `
  --cert /mosquitto/certs/rest-client.crt `
  --key /mosquitto/certs/rest-client.key `
  -t 'psfusion/business-event/v010/compartment/opened' `
  -v `
  -C 1
```



## Publish One Test MQTT Message

Use this command to run the existing test publisher once:

```powershell
docker compose run --rm mqtt-publisher
```

Use this command to publish directly from the running `mosquitto` container with the `rest-server` client certificate:

```powershell
docker compose exec mosquitto mosquitto_pub `
  -h localhost `
  -p 8883 `
  --cafile /mosquitto/certs/ca.crt `
  --cert /mosquitto/certs/rest-server.crt `
  --key /mosquitto/certs/rest-server.key `
  -t 'psfusion/business-event/v010/compartment/opened' `
  -m '{"test":"rest-server-cert"}'
```
