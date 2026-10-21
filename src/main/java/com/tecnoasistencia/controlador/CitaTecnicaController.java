package com.tecnoasistencia.controlador;

import com.tecnoasistencia.dao.CitaTecnicaDAO;
import com.tecnoasistencia.dao.ClienteDAO;
import com.tecnoasistencia.modelo.CitaTecnica;
import com.tecnoasistencia.modelo.Cliente;
import com.tecnoasistencia.utilidad.Alertas;
import com.tecnoasistencia.utilidad.Validaciones;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controlador de la vista de citas técnicas (cita-view.fxml).
 * Implementa las operaciones CRUD, validaciones de negocio, filtros dinámicos
 * y programación multihilo reactiva mediante javafx.concurrent.Task y ExecutorService.
 */
public class CitaTecnicaController {

    @FXML private ComboBox<Cliente> cmbCliente;
    @FXML private DatePicker dpFecha;
    @FXML private TextField txtHora;
    @FXML private TextField txtServicio;
    @FXML private TextField txtDescripcion;
    @FXML private ComboBox<String> cmbEstado;
    @FXML private TextField txtId;

    @FXML private ComboBox<String> cmbFiltroEstado;
    @FXML private DatePicker dpFiltroFecha;

    @FXML private TableView<CitaTecnica> tblCitas;
    @FXML private TableColumn<CitaTecnica, Integer> colId;
    @FXML private TableColumn<CitaTecnica, String> colCliente;
    @FXML private TableColumn<CitaTecnica, LocalDate> colFecha;
    @FXML private TableColumn<CitaTecnica, LocalTime> colHora;
    @FXML private TableColumn<CitaTecnica, String> colServicio;
    @FXML private TableColumn<CitaTecnica, String> colDescripcion;
    @FXML private TableColumn<CitaTecnica, String> colEstado;

    @FXML private Button btnGuardar;
    @FXML private Button btnActualizar;
    @FXML private Button btnEliminar;
    @FXML private Button btnRecargar;
    @FXML private Button btnExportar;
    @FXML private Label lblContador;
    @FXML private Label lblEstadoTarea;
    @FXML private ProgressIndicator progresoCarga;

    /**
     * DTO inmutable que transfiere de forma segura los datos leídos en el hilo de fondo a la UI.
     */
    public record ResultadoCargaCitas(
            List<Cliente> clientes,
            List<CitaTecnica> citas,
            long duracionMs
    ) {}

    /**
     * Pool de hilos con hilos demonio (daemon threads) para no bloquear el cierre de la JVM.
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("Thread-DB-Citas");
        thread.setDaemon(true);
        return thread;
    });

    private final CitaTecnicaDAO citaDAO = new CitaTecnicaDAO();
    private final ClienteDAO clienteDAO = new ClienteDAO();
    private final ObservableList<CitaTecnica> citas = FXCollections.observableArrayList();
    private final FilteredList<CitaTecnica> filtradas = new FilteredList<>(citas, cita -> true);
    private int idSeleccionado;

    @FXML
    public void initialize() {
        cmbEstado.getItems().setAll(Validaciones.ESTADOS);
        cmbFiltroEstado.getItems().add("Todos");
        cmbFiltroEstado.getItems().addAll(Validaciones.ESTADOS);
        cmbFiltroEstado.setValue("Todos");

        dpFecha.setEditable(false);
        dpFiltroFecha.setEditable(false);

        // Deshabilitar fechas pasadas en el selector visual para nuevas citas
        dpFecha.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                if (idSeleccionado == 0 && date != null && date.isBefore(LocalDate.now())) {
                    setDisable(true);
                    setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #94a3b8;");
                }
            }
        });

        // Filtros de entrada física en tiempo real para evitar desbordamientos
        txtHora.setTextFormatter(Validaciones.crearFiltroLongitud(5));
        txtServicio.setTextFormatter(Validaciones.crearFiltroLongitud(80));
        txtDescripcion.setTextFormatter(Validaciones.crearFiltroLongitud(255));

        cmbCliente.setCellFactory(lista -> celdaCliente());
        cmbCliente.setButtonCell(celdaCliente());

        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colCliente.setCellValueFactory(new PropertyValueFactory<>("nombreCliente"));
        colFecha.setCellValueFactory(new PropertyValueFactory<>("fecha"));
        colHora.setCellValueFactory(new PropertyValueFactory<>("hora"));
        colServicio.setCellValueFactory(new PropertyValueFactory<>("tipoServicio"));
        colDescripcion.setCellValueFactory(new PropertyValueFactory<>("descripcion"));
        colEstado.setCellValueFactory(new PropertyValueFactory<>("estado"));

        colFecha.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDate valor, boolean empty) {
                super.updateItem(valor, empty);
                setText(empty || valor == null ? null : valor.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            }
        });

        colHora.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LocalTime valor, boolean empty) {
                super.updateItem(valor, empty);
                setText(empty || valor == null ? null : valor.format(Validaciones.HORA));
            }
        });

        SortedList<CitaTecnica> ordenadas = new SortedList<>(filtradas);
        ordenadas.comparatorProperty().bind(tblCitas.comparatorProperty());
        tblCitas.setItems(ordenadas);
        tblCitas.setPlaceholder(new Label("No hay citas registradas para los filtros seleccionados."));

        tblCitas.getSelectionModel().selectedItemProperty().addListener((obs, anterior, actual) -> {
            if (actual != null) {
                cargarFormulario(actual);
            }
        });

        cmbFiltroEstado.valueProperty().addListener((obs, anterior, actual) -> aplicarFiltros());
        dpFiltroFecha.valueProperty().addListener((obs, anterior, actual) -> aplicarFiltros());

        limpiarFormulario();
        cargarDatos();
    }

    private ListCell<Cliente> celdaCliente() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Cliente cliente, boolean empty) {
                super.updateItem(cliente, empty);
                if (empty || cliente == null) {
                    setText(null);
                } else {
                    setText(cliente.getId() + " - " + cliente.getNombres() + " " + cliente.getApellidos()
                            + (cliente.isActivo() ? "" : " (inactivo)"));
                }
            }
        };
    }

    /**
     * Carga asíncrona y reactiva de clientes y citas técnicas mediante javafx.concurrent.Task.
     * Ejecuta las consultas SQL pesadas con JOIN en un hilo secundario (background thread),
     * garantizando que el JavaFX Application Thread jamás se congele y la interfaz permanezca 100% responsiva.
     */
    @FXML
    public void cargarDatos() {
        Task<ResultadoCargaCitas> task = new Task<>() {
            @Override
            protected ResultadoCargaCitas call() throws Exception {
                // Comprobación de concurrencia: este bloque se ejecuta en el background thread
                System.out.printf("[CONCURRENCIA] Inicio carga en hilo secundario: '%s' (isFxThread=%b)%n",
                        Thread.currentThread().getName(), Platform.isFxApplicationThread());

                updateMessage("Conectando con base de datos PostgreSQL...");
                updateProgress(0.1, 1.0);

                long inicio = System.currentTimeMillis();

                // 1. Cargar catálogo de clientes desde PostgreSQL
                updateMessage("Consultando catálogo de clientes...");
                updateProgress(0.3, 1.0);
                List<Cliente> clientesActuales = clienteDAO.listarClientes();

                // Demostración explícita de Platform.runLater() para sincronización manual
                Platform.runLater(() -> {
                    System.out.println("[Platform.runLater] Catálogo de clientes obtenido en background (" + clientesActuales.size() + " clientes).");
                });

                // Pausa pedagógica intencional (600ms) para percibir el indicador visual y probar la ventana en evaluación
                Thread.sleep(600);

                // 2. Cargar citas técnicas con JOIN hacia PostgreSQL
                updateMessage("Consultando historial de citas técnicas...");
                updateProgress(0.7, 1.0);
                List<CitaTecnica> citasActuales = citaDAO.listarCitas();

                // Pausa pedagógica complementaria (400ms)
                Thread.sleep(400);

                updateMessage("Sincronizando datos con la interfaz...");
                updateProgress(1.0, 1.0);

                long duracion = System.currentTimeMillis() - inicio;

                System.out.printf("[CONCURRENCIA] Carga finalizada en hilo '%s'. Citas: %d, Clientes: %d, Duración: %d ms.%n",
                        Thread.currentThread().getName(), citasActuales.size(), clientesActuales.size(), duracion);

                return new ResultadoCargaCitas(clientesActuales, citasActuales, duracion);
            }
        };

        // Vinculación reactiva de propiedades a los controles visuales (JavaFX reactive binding)
        if (btnRecargar != null) {
            btnRecargar.disableProperty().bind(task.runningProperty());
        }
        if (progresoCarga != null) {
            progresoCarga.visibleProperty().bind(task.runningProperty());
            progresoCarga.managedProperty().bind(task.runningProperty());
            progresoCarga.progressProperty().bind(task.progressProperty());
        }
        if (lblEstadoTarea != null) {
            lblEstadoTarea.textProperty().bind(task.messageProperty());
        }

        // Callback de éxito: ejecutado AUTOMÁTICAMENTE en el JavaFX Application Thread
        task.setOnSucceeded(event -> {
            if (lblEstadoTarea != null) {
                lblEstadoTarea.textProperty().unbind();
            }
            if (progresoCarga != null) {
                progresoCarga.progressProperty().unbind();
            }
            if (btnRecargar != null) {
                btnRecargar.disableProperty().unbind();
                btnRecargar.setDisable(false);
            }

            ResultadoCargaCitas resultado = task.getValue();

            System.out.printf("[CONCURRENCIA] Callback setOnSucceeded en hilo UI: '%s' (isFxThread=%b)%n",
                    Thread.currentThread().getName(), Platform.isFxApplicationThread());

            // Actualización segura de controles en el JavaFX Application Thread
            cmbCliente.getItems().setAll(resultado.clientes());
            citas.setAll(resultado.citas());
            aplicarFiltros();

            if (lblEstadoTarea != null) {
                lblEstadoTarea.setText(String.format("Listo: %d citas cargadas en %d ms",
                        resultado.citas().size(), resultado.duracionMs()));
            }
        });

        // Callback de fallo: ejecutado AUTOMÁTICAMENTE en el JavaFX Application Thread
        task.setOnFailed(event -> {
            if (lblEstadoTarea != null) {
                lblEstadoTarea.textProperty().unbind();
            }
            if (progresoCarga != null) {
                progresoCarga.progressProperty().unbind();
            }
            if (btnRecargar != null) {
                btnRecargar.disableProperty().unbind();
                btnRecargar.setDisable(false);
            }

            Throwable error = task.getException();
            System.err.printf("[CONCURRENCIA ERROR] Fallo en hilo background: %s%n",
                    error != null ? error.getMessage() : "Desconocido");

            if (lblEstadoTarea != null) {
                lblEstadoTarea.setText("Error al cargar datos desde PostgreSQL");
            }

            Alertas.errorDatos(error instanceof Exception ? (Exception) error : new Exception(error));
        });

        // Enviar la tarea al pool de hilos para su ejecución asíncrona
        executorService.submit(task);
    }

    private void cargarFormulario(CitaTecnica cita) {
        idSeleccionado = cita.getId();
        txtId.setText(String.valueOf(cita.getId()));

        cmbCliente.setValue(cmbCliente.getItems().stream()
                .filter(c -> c.getId() == cita.getClienteId())
                .findFirst()
                .orElse(null));

        dpFecha.setValue(cita.getFecha());
        txtHora.setText(cita.getHora().format(Validaciones.HORA));
        txtServicio.setText(cita.getTipoServicio());
        txtDescripcion.setText(cita.getDescripcion());
        cmbEstado.setValue(cita.getEstado());

        btnGuardar.setDisable(true);
        btnActualizar.setDisable(false);
        btnEliminar.setDisable(false);
    }

    private CitaTecnica leerFormulario() {
        Cliente cliente = cmbCliente.getValue();
        if (cliente == null) {
            throw new IllegalArgumentException("Debe seleccionar un cliente existente de la lista.");
        }

        // Regla de negocio: No agendar nuevas citas a clientes inactivos
        Validaciones.validarClienteParaCita(cliente, idSeleccionado != 0);

        if (dpFecha.getValue() == null) {
            throw new IllegalArgumentException("Seleccione la fecha de la cita.");
        }

        LocalTime hora = Validaciones.leerHora(txtHora.getText());

        if (cmbEstado.getValue() == null || !Validaciones.ESTADOS.contains(cmbEstado.getValue())) {
            throw new IllegalArgumentException("Seleccione un estado válido (" + String.join(", ", Validaciones.ESTADOS) + ").");
        }

        // Regla de negocio: No permitir fechas pasadas ni horas pasadas de hoy
        Validaciones.validarFechaHoraCita(dpFecha.getValue(), hora, idSeleccionado != 0, cmbEstado.getValue());

        String servicio = Validaciones.textoObligatorio(txtServicio.getText(), "El tipo de servicio", 3, 80);
        String descripcion = Validaciones.textoObligatorio(txtDescripcion.getText(), "La descripción", 5, 255);

        return new CitaTecnica(
                idSeleccionado,
                cliente.getId(),
                cliente.toString(),
                dpFecha.getValue(),
                hora,
                servicio,
                descripcion,
                cmbEstado.getValue()
        );
    }

    @FXML
    private void handleGuardar() {
        guardar(false);
    }

    @FXML
    private void handleActualizar() {
        guardar(true);
    }

    private void guardar(boolean actualizar) {
        try {
            if (actualizar && idSeleccionado == 0) {
                throw new IllegalArgumentException("Seleccione una cita técnica de la tabla para actualizar.");
            }

            CitaTecnica cita = leerFormulario();

            // Regla de negocio: Prevenir colisión de citas (doble cita al mismo cliente en la misma fecha y hora)
            if (citaDAO.existeCitaCliente(cita.getClienteId(), cita.getFecha(), cita.getHora(), cita.getId())) {
                Alertas.mostrar(Alert.AlertType.WARNING, "Conflicto de Horario",
                        "El cliente '" + cita.getNombreCliente() + "' ya tiene una cita técnica agendada para el "
                                + cita.getFechaFormateada() + " a las " + cita.getHoraFormateada()
                                + ".\n\nPor favor asigne un horario o fecha diferente para evitar colisión de citas.");
                return;
            }

            String accion = actualizar ? "actualizar" : "registrar";
            String mensajeConfirmacion = "¿Desea " + accion + " la cita para el cliente "
                    + cita.getNombreCliente() + " el día " + cita.getFechaFormateada()
                    + " a las " + cita.getHoraFormateada() + "?";

            if (!Alertas.confirmar(actualizar ? "Confirmar Actualización" : "Confirmar Registro", mensajeConfirmacion)) {
                return;
            }

            boolean exito = actualizar ? citaDAO.actualizarCita(cita) : citaDAO.insertarCita(cita);

            if (!exito) {
                Alertas.mostrar(Alert.AlertType.WARNING, "Operación no completada",
                        "No se pudo completar la operación. El registro podría no existir en la base de datos.");
                return;
            }

            Alertas.mostrar(Alert.AlertType.INFORMATION, "Operación Exitosa",
                    "Cita técnica " + (actualizar ? "actualizada" : "registrada") + " correctamente.");

            limpiarFormulario();
            cargarDatos();
        } catch (IllegalArgumentException e) {
            Alertas.mostrar(Alert.AlertType.WARNING, "Validación de Formulario", e.getMessage());
        } catch (SQLException e) {
            Alertas.errorDatos(e);
        }
    }

    @FXML
    private void handleEliminar() {
        if (idSeleccionado == 0) {
            Alertas.mostrar(Alert.AlertType.WARNING, "Selección Requerida",
                    "Seleccione una cita técnica de la tabla para eliminarla.");
            return;
        }

        if (!Alertas.confirmar("Confirmar Eliminación",
                "¿Está seguro de eliminar permanentemente la cita técnica #" + idSeleccionado + "?")) {
            return;
        }

        try {
            if (citaDAO.eliminarCita(idSeleccionado)) {
                Alertas.mostrar(Alert.AlertType.INFORMATION, "Operación Exitosa",
                        "Cita técnica #" + idSeleccionado + " eliminada permanentemente.");
                limpiarFormulario();
                cargarDatos();
            } else {
                Alertas.mostrar(Alert.AlertType.WARNING, "Registro No Encontrado",
                        "La cita ya no existe en la base de datos. Se actualizará la lista.");
                cargarDatos();
            }
        } catch (SQLException e) {
            Alertas.errorDatos(e);
        }
    }

    @FXML
    public void limpiarFormulario() {
        tblCitas.getSelectionModel().clearSelection();
        idSeleccionado = 0;
        txtId.clear();
        cmbCliente.setValue(null);
        dpFecha.setValue(null);
        txtHora.clear();
        txtServicio.clear();
        txtDescripcion.clear();
        cmbEstado.setValue("Pendiente");

        btnGuardar.setDisable(false);
        btnActualizar.setDisable(true);
        btnEliminar.setDisable(true);
    }

    @FXML
    private void aplicarFiltros() {
        String estado = cmbFiltroEstado.getValue();
        LocalDate fecha = dpFiltroFecha.getValue();

        filtradas.setPredicate(cita -> {
            boolean coincideEstado = estado == null || "Todos".equals(estado) || estado.equalsIgnoreCase(cita.getEstado());
            boolean coincideFecha = fecha == null || fecha.equals(cita.getFecha());
            return coincideEstado && coincideFecha;
        });

        lblContador.setText("Citas mostradas: " + filtradas.size() + " de " + citas.size());
    }

    @FXML
    private void handleLimpiarFiltros() {
        cmbFiltroEstado.setValue("Todos");
        dpFiltroFecha.setValue(null);
        aplicarFiltros();
    }

    /**
     * Generación de reporte/exportación CSV en segundo plano (Requisito de concurrencia para operaciones pesadas).
     * Muestra reactividad mediante Task, indicador de progreso y Platform.runLater().
     */
    @FXML
    public void handleExportarReporte() {
        if (citas.isEmpty()) {
            Alertas.mostrar(Alert.AlertType.WARNING, "Exportación", "No hay citas disponibles para exportar.");
            return;
        }

        Task<File> exportTask = new Task<>() {
            @Override
            protected File call() throws Exception {
                System.out.printf("[CONCURRENCIA EXPORT] Iniciando exportación en hilo: '%s' (isFxThread=%b)%n",
                        Thread.currentThread().getName(), Platform.isFxApplicationThread());

                updateMessage("Preparando datos para exportación CSV...");
                updateProgress(0.05, 1.0);

                File archivoSalida = new File("reporte_citas_tecnicas.csv");

                try (PrintWriter writer = new PrintWriter(new FileWriter(archivoSalida))) {
                    writer.println("ID,Cliente,Fecha,Hora,Servicio,Descripcion,Estado");

                    List<CitaTecnica> listaExportar = List.copyOf(citas);
                    int total = listaExportar.size();

                    for (int i = 0; i < total; i++) {
                        CitaTecnica c = listaExportar.get(i);
                        writer.printf("%d,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                                c.getId(),
                                c.getNombreCliente().replace("\"", "\"\""),
                                c.getFecha(),
                                c.getHora(),
                                c.getTipoServicio().replace("\"", "\"\""),
                                c.getDescripcion().replace("\"", "\"\""),
                                c.getEstado());

                        updateProgress(i + 1, total);
                        updateMessage(String.format("Exportando cita %d de %d...", (i + 1), total));

                        // Breve pausa para percibir el progreso gradual en la UI
                        Thread.sleep(30);
                    }
                }

                // Demostración de sincronización manual con Platform.runLater()
                Platform.runLater(() -> {
                    System.out.println("[Platform.runLater] Archivo CSV escrito exitosamente en disco.");
                });

                return archivoSalida;
            }
        };

        if (btnExportar != null) {
            btnExportar.disableProperty().bind(exportTask.runningProperty());
        }
        if (progresoCarga != null) {
            progresoCarga.visibleProperty().bind(exportTask.runningProperty());
            progresoCarga.managedProperty().bind(exportTask.runningProperty());
            progresoCarga.progressProperty().bind(exportTask.progressProperty());
        }
        if (lblEstadoTarea != null) {
            lblEstadoTarea.textProperty().bind(exportTask.messageProperty());
        }

        exportTask.setOnSucceeded(e -> {
            if (lblEstadoTarea != null) lblEstadoTarea.textProperty().unbind();
            if (progresoCarga != null) progresoCarga.progressProperty().unbind();
            if (btnExportar != null) {
                btnExportar.disableProperty().unbind();
                btnExportar.setDisable(false);
            }

            File archivoGenerado = exportTask.getValue();
            if (lblEstadoTarea != null) {
                lblEstadoTarea.setText("Reporte generado: " + archivoGenerado.getName());
            }

            Alertas.mostrar(Alert.AlertType.INFORMATION, "Exportación Exitosa",
                    "El reporte de citas técnicas se generó en segundo plano:\n\n"
                            + archivoGenerado.getAbsolutePath() + "\n\nTotal de citas exportadas: " + citas.size());
        });

        exportTask.setOnFailed(e -> {
            if (lblEstadoTarea != null) lblEstadoTarea.textProperty().unbind();
            if (progresoCarga != null) progresoCarga.progressProperty().unbind();
            if (btnExportar != null) {
                btnExportar.disableProperty().unbind();
                btnExportar.setDisable(false);
            }

            Throwable ex = exportTask.getException();
            if (lblEstadoTarea != null) {
                lblEstadoTarea.setText("Error al generar reporte.");
            }
            Alertas.mostrar(Alert.AlertType.ERROR, "Error de Exportación",
                    "No se pudo completar la exportación: " + (ex != null ? ex.getMessage() : "Error desconocido"));
        });

        executorService.submit(exportTask);
    }
}