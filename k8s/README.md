# Kubernetes variant

Same observability model, different collection mechanism:

| Docker Compose | Kubernetes |
|---|---|
| Filebeat autodiscover (docker provider) | Filebeat DaemonSet, `kubernetes` autodiscover provider |
| container labels carry the log hints | pod **annotations** carry the log hints |
| `add_docker_metadata` | `add_kubernetes_metadata` |
| a single Metricbeat container | Metricbeat DaemonSet (node) + Deployment (kube-state-metrics) |
| APM Server container | Elastic APM Server Deployment, or ECS Operator (`elastic/eck`) |

The app itself changes **nothing**: it still writes ECS JSON to stdout. That is the
payoff of instrumenting at the application layer rather than the platform layer —
your collection strategy can change without a code change.

Apply with:

```bash
kubectl apply -f k8s/
```
