package dev.romeo.btctradingengine.alerting;

/**
 * Envia alertas para um canal externo quando algo grave acontece (ordem falhou,
 * reconciliacao falhou, stream caiu por tempo demais). Implementacoes devem ser
 * best-effort: uma falha ao entregar o alerta nunca pode derrubar o chamador.
 */
public interface AlertNotifier {
    void alert(String message);
}
