using System.Buffers.Binary;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;
using System.Net;
using System.Net.Sockets;
using System.Numerics;
using System.Text.Json;
using OpenCvSharp;
using OpenCvSharp.Extensions;
using Size = System.Drawing.Size;

namespace VRoom;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new DashboardForm());
    }
}

internal sealed class DashboardForm : Form
{
    private readonly UdpClient _driverRelay = new();
    private readonly Label _status = MakeValue("Остановлено");
    private readonly Label _head = MakeValue("Нет данных");
    private readonly Label _hands = MakeValue("Нет данных");
    private readonly Label _endpoint = MakeValue("UDP :9000 · TCP :9001");
    private readonly Label _cameraStatus = MakeValue("Ожидание телефона · TCP 9001");
    private readonly PictureBox _camera = new() { Dock = DockStyle.Fill, BackColor = Color.Black, SizeMode = PictureBoxSizeMode.Zoom };
    private readonly TextBox _log = new()
    {
        Multiline = true, ReadOnly = true, WordWrap = false,
        ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill,
        BackColor = Color.FromArgb(12, 18, 30), ForeColor = Color.Gainsboro,
        BorderStyle = BorderStyle.None, Font = new Font("Consolas", 9.5f)
    };
    private readonly Button _toggle = new() { Text = "Запустить VRoom", AutoSize = true };
    private CancellationTokenSource? _stop;
    private Task? _listener;
    private Task? _cameraListener;
    private TcpListener? _tcpListener;
    private TcpListener? _displayListener;
    private Task? _displayTask;
    private long _cameraFrames;
    private PointF[][] _handSkeleton = Array.Empty<PointF[]>();
    private PointF[] _bodySkeleton = Array.Empty<PointF>();
    private readonly Label _body = MakeValue("Нет данных");
    private static readonly (int A, int B)[] HandBones = { (0,1),(1,2),(2,3),(3,4),(0,5),(5,6),(6,7),(7,8),(5,9),(9,10),(10,11),(11,12),(9,13),(13,14),(14,15),(15,16),(13,17),(17,18),(18,19),(19,20),(0,17) };
    private static readonly (int A, int B)[] BodyBones = { (11,12),(11,13),(13,15),(15,17),(15,19),(15,21),(12,14),(14,16),(16,18),(16,20),(16,22),(11,23),(12,24),(23,24),(23,25),(25,27),(27,29),(29,31),(27,31),(24,26),(26,28),(28,30),(30,32),(28,32),(0,1),(1,2),(2,3),(3,7),(0,4),(4,5),(5,6),(6,8),(9,10) };
    private readonly PictureBox _pcCamera = new() { Dock = DockStyle.Fill, BackColor = Color.Black, SizeMode = PictureBoxSizeMode.Zoom };
    private readonly ComboBox _pcCameraIndex = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 100 };
    private readonly Button _pcCameraToggle = new() { Text = "Запустить веб-камеру", AutoSize = true };
    private CancellationTokenSource? _pcCameraStop;
    private Task? _pcCameraTask;
    private readonly TrackBar _armSensitivity = new() { Minimum = 1, Maximum = 100, Value = 50, TickFrequency = 10, Width = 260 };
    private readonly TrackBar _walkSensitivity = new() { Minimum = 1, Maximum = 100, Value = 40, TickFrequency = 10, Width = 260 };
    private readonly TrackBar _motionSmoothing = new() { Minimum = 0, Maximum = 100, Value = 65, TickFrequency = 10, Width = 260 };
    private readonly CheckBox _enableOverlay = new() { Text = "Включить пространственное меню (профиль)", Checked = true, AutoSize = true };
    private readonly CheckBox _enableArmSwing = new() { Text = "Arm Swing · движение руками", Checked = true, AutoSize = true };
    private readonly CheckBox _enableWalkInPlace = new() { Text = "Walk in Place · шаги на месте (нужна камера тела)", AutoSize = true };
    private readonly ComboBox _locomotionMode = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 220 };
    private readonly ComboBox _overlayAnchor = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 220 };
    private readonly NumericUpDown _overlayScale = new() { Minimum = 50, Maximum = 200, Value = 100, Width = 100 };
    private readonly NumericUpDown _overlayDistance = new() { Minimum = 30, Maximum = 200, Value = 80, Width = 100 };
    private readonly Label _profileStatus = new() { AutoSize = true, ForeColor = Color.LightSteelBlue, Text = "Профиль не сохранён" };

    public DashboardForm()
    {
        Text = "VRoom Desktop";
        MinimumSize = new Size(900, 680);
        Size = new Size(1180, 820);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(17, 25, 41);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20), ColumnCount = 1, RowCount = 5 };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 68));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 32));
        Controls.Add(root);

        root.Controls.Add(new Label
        {
            Text = "VRoom", AutoSize = true, Font = new Font("Segoe UI", 28, FontStyle.Bold),
            ForeColor = Color.FromArgb(73, 226, 178), Padding = new Padding(0, 0, 0, 4)
        });
        root.Controls.Add(new Label
        {
            Text = "Wireless VR · Windows host · двусторонняя передача по Wi-Fi",
            AutoSize = true, ForeColor = Color.LightSteelBlue, Padding = new Padding(0, 0, 0, 14)
        });

        var cards = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, RowCount = 3, Padding = new Padding(0, 0, 0, 10) };
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        cards.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        cards.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        cards.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        cards.Controls.Add(MakeCard("СОСТОЯНИЕ", _status), 0, 0);
        cards.Controls.Add(MakeCard("ОРИЕНТАЦИЯ ГОЛОВЫ", _head), 1, 0);
        cards.Controls.Add(MakeCard("РУКИ / LANDMARKS", _hands), 0, 1);
        cards.Controls.Add(MakeCard("ТЕЛО / POSE", _body), 1, 1);
        cards.Controls.Add(MakeCard("АДРЕСА И ПОРТЫ", _endpoint), 0, 2);
        root.Controls.Add(cards);

        var cameraPanel = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1, BackColor = Color.FromArgb(27, 39, 64), Padding = new Padding(10) };
        cameraPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        cameraPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var cameraHeader = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        cameraHeader.Controls.Add(new Label { Text = "КАМЕРА ТЕЛЕФОНА", AutoSize = true, ForeColor = Color.FromArgb(145, 171, 199), Font = new Font("Segoe UI", 9, FontStyle.Bold), Padding = new Padding(0, 6, 12, 0) });
        cameraHeader.Controls.Add(_cameraStatus);
        cameraPanel.Controls.Add(cameraHeader, 0, 0);
        cameraPanel.Controls.Add(_camera, 0, 1);
        _camera.Paint += DrawHandSkeleton;
        var tabs = new TabControl { Dock = DockStyle.Fill };
        var phoneTab = new TabPage("Камера телефона") { BackColor = BackColor };
        phoneTab.Controls.Add(cameraPanel);
        tabs.TabPages.Add(phoneTab);
        tabs.TabPages.Add(BuildPcCameraTab());
        tabs.TabPages.Add(BuildSettingsTab());
        root.Controls.Add(tabs);
        LoadProfile();

        var bottom = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        bottom.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        bottom.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 0, 0, 8) };
        _toggle.BackColor = Color.FromArgb(73, 226, 178);
        _toggle.ForeColor = Color.FromArgb(8, 28, 28);
        _toggle.FlatStyle = FlatStyle.Flat;
        _toggle.Font = new Font("Segoe UI", 10, FontStyle.Bold);
        _toggle.Click += async (_, _) => await ToggleListenerAsync();
        actions.Controls.Add(_toggle);
        actions.Controls.Add(new Label { Text = "Порты: UDP 9000 · TCP 9001 (камера) · TCP 9002 (экран ПК)", AutoSize = true, ForeColor = Color.LightSteelBlue, Padding = new Padding(10, 8, 0, 0) });
        bottom.Controls.Add(actions, 0, 0);
        bottom.Controls.Add(_log, 0, 1);
        root.Controls.Add(bottom);
        Log("VRoom Desktop готов. Запусти приём, затем подключи телефон к одному Wi-Fi.");
    }

    private TabPage BuildPcCameraTab()
    {
        var page = new TabPage("Веб-камера ПК") { BackColor = BackColor };
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), RowCount = 3, ColumnCount = 1 };
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize)); layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var header = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 0, 0, 8) };
        header.Controls.Add(new Label { Text = "Внешняя камера для отслеживания тела", AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI", 12, FontStyle.Bold), Padding = new Padding(0, 7, 12, 0) });
        for (var i = 0; i < 6; i++) _pcCameraIndex.Items.Add("Камера " + i);
        _pcCameraIndex.SelectedIndex = 0;
        header.Controls.Add(_pcCameraIndex);
        _pcCameraToggle.Click += async (_, _) => { if (_pcCameraStop is null) await StartPcCameraAsync(); else await StopPcCameraAsync(); };
        header.Controls.Add(_pcCameraToggle); layout.Controls.Add(header, 0, 0); layout.Controls.Add(_pcCamera, 0, 1);
        layout.Controls.Add(new Label { Text = "Предпросмотр веб-камеры ПК. Следующий этап подключит оценку позы тела и передачу суставов в VR runtime.", AutoSize = true, ForeColor = Color.LightSteelBlue, Padding = new Padding(0, 8, 0, 0) }, 0, 2);
        page.Controls.Add(layout); return page;
    }

    private async Task StartPcCameraAsync()
    {
        var index = Math.Max(0, _pcCameraIndex.SelectedIndex);
        _pcCameraStop = new CancellationTokenSource();
        _pcCameraIndex.Enabled = false; _pcCameraToggle.Text = "Остановить веб-камеру";
        var token = _pcCameraStop.Token;
        _pcCameraTask = Task.Run(async () =>
        {
            using var capture = new VideoCapture(index, VideoCaptureAPIs.DSHOW);
            if (!capture.IsOpened()) { UpdateUi(() => { Log("Не удалось открыть веб-камеру " + index); }); return; }
            capture.Set(VideoCaptureProperties.FrameWidth, 1280); capture.Set(VideoCaptureProperties.FrameHeight, 720);
            using var frame = new Mat();
            while (!token.IsCancellationRequested)
            {
                if (!capture.Read(frame) || frame.Empty()) { await Task.Delay(50, token).ConfigureAwait(false); continue; }
                var bitmap = BitmapConverter.ToBitmap(frame);
                UpdateUi(() => { var old = _pcCamera.Image; _pcCamera.Image = bitmap; old?.Dispose(); });
                await Task.Delay(33, token).ConfigureAwait(false);
            }
        }, token);
        try { await _pcCameraTask; } catch (OperationCanceledException) { } catch (Exception ex) { Log("Ошибка веб-камеры ПК: " + ex.Message); }
        _pcCameraStop?.Dispose(); _pcCameraStop = null; _pcCameraIndex.Enabled = true; _pcCameraToggle.Text = "Запустить веб-камеру";
    }

    private async Task StopPcCameraAsync()
    {
        _pcCameraStop?.Cancel();
        if (_pcCameraTask is not null) { try { await _pcCameraTask; } catch (OperationCanceledException) { } catch (Exception ex) { Log(ex.Message); } }
        _pcCameraStop?.Dispose(); _pcCameraStop = null; _pcCameraIndex.Enabled = true; _pcCameraToggle.Text = "Запустить веб-камеру";
        var old = _pcCamera.Image; _pcCamera.Image = null; old?.Dispose();
    }

    private TabPage BuildSettingsTab()
    {
        var page = new TabPage("VR и управление") { BackColor = BackColor, AutoScroll = true };
        var stack = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Padding = new Padding(20) };
        stack.Controls.Add(Section("Пространственный оверлей")); stack.Controls.Add(_enableOverlay);
        stack.Controls.Add(new Label { Text = "Параметры будущего меню: место закрепления, масштаб и расстояние.", AutoSize = true, ForeColor = Color.LightSteelBlue });
        _overlayAnchor.Items.AddRange(new object[] { "Перед глазами", "Левое запястье", "Правое запястье", "Закрепить в пространстве" }); _overlayAnchor.SelectedIndex = 0;
        stack.Controls.Add(new Label { Text = "Закрепление меню", AutoSize = true, ForeColor = Color.White }); stack.Controls.Add(_overlayAnchor);
        stack.Controls.Add(LabeledNumeric("Масштаб меню (%)", _overlayScale));
        stack.Controls.Add(LabeledNumeric("Расстояние (см)", _overlayDistance));
        stack.Controls.Add(Section("Передвижение"));
        _locomotionMode.Items.AddRange(new object[] { "Arm Swing", "Walk in Place", "Hybrid", "Отключено" }); _locomotionMode.SelectedIndex = 0;
        stack.Controls.Add(new Label { Text = "Режим передвижения", AutoSize = true, ForeColor = Color.White }); stack.Controls.Add(_locomotionMode);
        stack.Controls.Add(_enableArmSwing); stack.Controls.Add(_enableWalkInPlace);
        stack.Controls.Add(LabeledSlider("Чувствительность Arm Swing", _armSensitivity));
        stack.Controls.Add(LabeledSlider("Чувствительность ходьбы", _walkSensitivity));
        stack.Controls.Add(LabeledSlider("Сглаживание движения", _motionSmoothing));
        stack.Controls.Add(new Label { Text = "Это параметры будущего motion engine, пока они не управляют движением в игре.", AutoSize = true, ForeColor = Color.Khaki });
        stack.Controls.Add(Section("Сеть и качество"));
        stack.Controls.Add(new Label { Text = "Телеметрия: UDP 9000 · Видео телефона: TCP 9001. Используйте частную сеть; не открывайте порты в интернет.", AutoSize = true, ForeColor = Color.LightSteelBlue, MaximumSize = new Size(800, 0) });
        stack.Controls.Add(new Label { Text = "Рекомендуется подключить ПК к роутеру по Ethernet, телефон — по Wi-Fi того же роутера.", AutoSize = true, ForeColor = Color.LightSteelBlue });
        var save = new Button { Text = "Сохранить профиль VRoom", AutoSize = true, BackColor = Color.FromArgb(73, 226, 178), ForeColor = Color.FromArgb(8, 28, 28), FlatStyle = FlatStyle.Flat };
        save.Click += (_, _) => SaveProfile(); stack.Controls.Add(save); stack.Controls.Add(_profileStatus);
        page.Controls.Add(stack); return page;
    }

    private static Control LabeledNumeric(string title, NumericUpDown input) { var panel = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, WrapContents = false, AutoSize = true }; panel.Controls.Add(new Label { Text = title, AutoSize = true, ForeColor = Color.White, Padding = new Padding(0, 5, 10, 0) }); panel.Controls.Add(input); return panel; }

    private void SaveProfile()
    {
        try
        {
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VRoom");
            Directory.CreateDirectory(folder);
            var profile = new { overlayEnabled = _enableOverlay.Checked, overlayAnchor = _overlayAnchor.SelectedIndex, overlayScale = _overlayScale.Value, overlayDistanceCm = _overlayDistance.Value, locomotionMode = _locomotionMode.SelectedIndex, armSwingEnabled = _enableArmSwing.Checked, walkInPlaceEnabled = _enableWalkInPlace.Checked, armSensitivity = _armSensitivity.Value, walkSensitivity = _walkSensitivity.Value, motionSmoothing = _motionSmoothing.Value };
            File.WriteAllText(Path.Combine(folder, "settings.json"), JsonSerializer.Serialize(profile, new JsonSerializerOptions { WriteIndented = true }));
            _profileStatus.Text = "Профиль сохранён: " + folder;
            Log("Профиль настроек VRoom сохранён.");
        }
        catch (Exception ex) { _profileStatus.Text = "Ошибка сохранения: " + ex.Message; }
    }

    private void LoadProfile()
    {
        try
        {
            var file = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VRoom", "settings.json");
            if (!File.Exists(file)) return;
            using var doc = JsonDocument.Parse(File.ReadAllText(file)); var p = doc.RootElement;
            if (p.TryGetProperty("overlayEnabled", out var oe)) _enableOverlay.Checked = oe.GetBoolean();
            if (p.TryGetProperty("overlayAnchor", out var oa)) _overlayAnchor.SelectedIndex = Math.Clamp(oa.GetInt32(), 0, Math.Max(0, _overlayAnchor.Items.Count - 1));
            if (p.TryGetProperty("overlayScale", out var os)) _overlayScale.Value = Math.Clamp(os.GetDecimal(), _overlayScale.Minimum, _overlayScale.Maximum);
            if (p.TryGetProperty("overlayDistanceCm", out var od)) _overlayDistance.Value = Math.Clamp(od.GetDecimal(), _overlayDistance.Minimum, _overlayDistance.Maximum);
            if (p.TryGetProperty("locomotionMode", out var lm)) _locomotionMode.SelectedIndex = Math.Clamp(lm.GetInt32(), 0, Math.Max(0, _locomotionMode.Items.Count - 1));
            if (p.TryGetProperty("armSwingEnabled", out var ae)) _enableArmSwing.Checked = ae.GetBoolean();
            if (p.TryGetProperty("walkInPlaceEnabled", out var we)) _enableWalkInPlace.Checked = we.GetBoolean();
            if (p.TryGetProperty("armSensitivity", out var ass)) _armSensitivity.Value = Math.Clamp(ass.GetInt32(), _armSensitivity.Minimum, _armSensitivity.Maximum);
            if (p.TryGetProperty("walkSensitivity", out var wss)) _walkSensitivity.Value = Math.Clamp(wss.GetInt32(), _walkSensitivity.Minimum, _walkSensitivity.Maximum);
            if (p.TryGetProperty("motionSmoothing", out var ms)) _motionSmoothing.Value = Math.Clamp(ms.GetInt32(), _motionSmoothing.Minimum, _motionSmoothing.Maximum);
            _profileStatus.Text = "Загружен сохранённый профиль";
        }
        catch (Exception ex) { _profileStatus.Text = "Не удалось загрузить профиль: " + ex.Message; }
    }

    private static Label Section(string text) => new() { Text = text, AutoSize = true, ForeColor = Color.FromArgb(73, 226, 178), Font = new Font("Segoe UI", 14, FontStyle.Bold), Padding = new Padding(0, 18, 0, 6) };
    private static Control LabeledSlider(string title, TrackBar slider) { var panel = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true }; panel.Controls.Add(new Label { Text = title, AutoSize = true, ForeColor = Color.White }); panel.Controls.Add(slider); return panel; }
    private static Label MakeValue(string value) => new()
    {
        Text = value, AutoSize = true, ForeColor = Color.White,
        Font = new Font("Segoe UI", 11, FontStyle.Bold), MaximumSize = new Size(520, 0)
    };

    private static Control MakeCard(string title, Label value)
    {
        var panel = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(27, 39, 64), Padding = new Padding(12), Margin = new Padding(0, 0, 10, 8), AutoSize = true };
        var stack = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoSize = true };
        stack.Controls.Add(new Label { Text = title, AutoSize = true, ForeColor = Color.FromArgb(145, 171, 199), Font = new Font("Segoe UI", 8, FontStyle.Bold) });
        stack.Controls.Add(value);
        panel.Controls.Add(stack);
        return panel;
    }

    private async Task ToggleListenerAsync()
    {
        if (_stop is not null)
        {
            _stop.Cancel();
            _tcpListener?.Stop();
            _displayListener?.Stop();
            _toggle.Enabled = false;
            if (_listener is not null) { try { await _listener; } catch { } }
            if (_cameraListener is not null) { try { await _cameraListener; } catch { } }
            if (_displayTask is not null) { try { await _displayTask; } catch { } }
            _stop.Dispose(); _stop = null; _listener = null; _cameraListener = null; _displayTask = null; _tcpListener = null; _displayListener = null;
            _toggle.Enabled = true; _toggle.Text = "Запустить VRoom";
            _status.Text = "Остановлено";
            _cameraStatus.Text = "Ожидание телефона · TCP 9001";
            Log("Сетевые службы остановлены.");
            return;
        }

        _stop = new CancellationTokenSource();
        _toggle.Text = "Остановить VRoom";
        _status.Text = "Службы запущены";
        _endpoint.Text = string.Join(", ", GetLocalAddresses()) + " · UDP 9000 · TCP 9001/9002";
        _listener = ListenTrackingAsync(_stop.Token);
        _tcpListener = new TcpListener(IPAddress.Any, 9001);
        _cameraListener = ListenCameraAsync(_stop.Token);
        _displayListener = new TcpListener(IPAddress.Any, 9002);
        _displayListener.Start(1);
        _displayTask = StreamDesktopPreviewAsync(_stop.Token);
        Log("UDP 9000, TCP 9001 (камера) и TCP 9002 (экран ПК → телефон) запущены.");
    }

    private static string[] GetLocalAddresses() =>
        Dns.GetHostEntry(Dns.GetHostName()).AddressList
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
            .Select(a => a.ToString()).Distinct().ToArray();

    private async Task ListenCameraAsync(CancellationToken token)
    {
        var listener = _tcpListener!;
        try
        {
            listener.Start(2);
            while (!token.IsCancellationRequested)
            {
                using var client = await listener.AcceptTcpClientAsync(token);
                client.NoDelay = true;
                Log($"Подключена камера телефона: {client.Client.RemoteEndPoint}");
                using var stream = client.GetStream();
                while (!token.IsCancellationRequested && client.Connected)
                {
                    var header = new byte[4];
                    if (!await ReadExactlyAsync(stream, header, token)) break;
                    var length = BinaryPrimitives.ReadInt32BigEndian(header);
                    if (length is < 4 or > 1_500_000) throw new InvalidDataException("Некорректный размер JPEG-кадра");
                    var bytes = new byte[length];
                    if (!await ReadExactlyAsync(stream, bytes, token)) break;
                    using var memory = new MemoryStream(bytes);
                    using var decoded = Image.FromStream(memory);
                    var frame = new Bitmap(decoded);
                    UpdateUi(() =>
                    {
                        var old = _camera.Image;
                        _camera.Image = frame;
                        old?.Dispose();
                        _cameraStatus.Text = $"Поток активен · {Interlocked.Increment(ref _cameraFrames)} кадров";
                    });
                }
                Log("Камера телефона отключилась.");
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (SocketException) when (token.IsCancellationRequested) { }
        catch (ObjectDisposedException) when (token.IsCancellationRequested) { }
        catch (Exception ex) { Log("Ошибка видеопотока: " + ex.Message); }
    }

    private async Task StreamDesktopPreviewAsync(CancellationToken token)
    {
        var listener = _displayListener!;
        try
        {
            while (!token.IsCancellationRequested)
            {
                using var phone = await listener.AcceptTcpClientAsync(token);
                phone.NoDelay = true;
                Log("Подключён VR-экран телефона: " + phone.Client.RemoteEndPoint);
                try
                {
                    using var phoneStream = phone.GetStream();
                    using var driver = new TcpClient();
                    while (!token.IsCancellationRequested)
                    {
                        try
                        {
                            await driver.ConnectAsync(IPAddress.Loopback, 9004, token);
                            break;
                        }
                        catch (SocketException)
                        {
                            await Task.Delay(1000, token);
                        }
                    }
                    driver.NoDelay = true;
                    Log("Получен канал кадров SteamVR OpenVR: 127.0.0.1:9004");
                    using var driverStream = driver.GetStream();
                    while (!token.IsCancellationRequested)
                    {
                        var header = new byte[4];
                        if (!await ReadExactlyAsync(driverStream, header, token)) break;
                        var length = BinaryPrimitives.ReadInt32BigEndian(header);
                        if (length is < 4 or > 4_000_000)
                            throw new InvalidDataException("OpenVR передал кадр недопустимого размера");
                        var frame = new byte[length];
                        if (!await ReadExactlyAsync(driverStream, frame, token)) break;
                        await phoneStream.WriteAsync(header, token);
                        await phoneStream.WriteAsync(frame, token);
                    }
                }
                catch (IOException ex) { Log("VR-экран отключился: " + ex.Message); }
                catch (SocketException ex) when (!token.IsCancellationRequested) { Log("Канал SteamVR: " + ex.Message); }
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (SocketException) when (token.IsCancellationRequested) { }
        catch (ObjectDisposedException) when (token.IsCancellationRequested) { }
        catch (Exception ex) { Log("Ошибка передачи OpenVR-кадров: " + ex.Message); }
    }

    private static async Task<bool> ReadExactlyAsync(NetworkStream stream, byte[] buffer, CancellationToken token)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var count = await stream.ReadAsync(buffer.AsMemory(offset), token);
            if (count == 0) return false;
            offset += count;
        }
        return true;
    }

    private async Task ListenTrackingAsync(CancellationToken token)
    {
        using var udp = new UdpClient(new IPEndPoint(IPAddress.Any, 9000));
        using var registration = token.Register(() => udp.Close());
        try
        {
            while (!token.IsCancellationRequested)
            {
                var packet = await udp.ReceiveAsync(token);
                try
                {
                    using var json = JsonDocument.Parse(packet.Buffer);
                    var root = json.RootElement;
                    var type = RequiredString(root, "type");
                    var version = root.GetProperty("version").GetInt32();
                    switch (type, version)
                    {
                        case ("head_pose", 1):
                        {
                            var q = root.GetProperty("orientation");
                            var pose = new Quaternion(Finite(q, "x"), Finite(q, "y"), Finite(q, "z"), Finite(q, "w"));
                            if (pose.Length() is < 0.5f or > 1.5f) throw new InvalidDataException("Некорректная длина quaternion");
                            pose = Quaternion.Normalize(pose);
                            UpdateUi(() => _head.Text = $"x {pose.X:F2} · y {pose.Y:F2} · z {pose.Z:F2} · w {pose.W:F2}");
                            // Relay the validated phone IMU packet to the SteamVR driver process.
                            _ = _driverRelay.SendAsync(packet.Buffer, packet.Buffer.Length, new IPEndPoint(IPAddress.Loopback, 9003));
                            break;
                        }
                        case ("body_pose", 1):
                        {
                            var points = root.GetProperty("landmarks");
                            if (points.ValueKind != JsonValueKind.Array || (points.GetArrayLength() != 0 && points.GetArrayLength() != 33))
                                throw new InvalidDataException("Ожидалось 33 точки тела или пустой список");
                            var body = new PointF[points.GetArrayLength()];
                            var index = 0;
                            foreach (var point in points.EnumerateArray())
                            {
                                var x = Finite(point, "x"); var y = Finite(point, "y"); _ = Finite(point, "z");
                                if (x is < 0f or > 1f || y is < 0f or > 1f) throw new InvalidDataException("Координаты тела должны быть в [0,1]");
                                body[index++] = new PointF(x, y);
                            }
                            var snapshot = body;
                            UpdateUi(() => { _bodySkeleton = snapshot; _body.Text = snapshot.Length == 0 ? "Тело не обнаружено" : "33 точки · скелет активен"; _camera.Invalidate(); });
                            break;
                        }
                        case ("hand_pose", 1):
                        {
                            var array = root.GetProperty("hands");
                            if (array.ValueKind != JsonValueKind.Array) throw new InvalidDataException("Некорректный список рук");
                            var handCount = array.GetArrayLength();
                            if (handCount > 2) throw new InvalidDataException("Некорректный список рук");
                            var descriptions = new List<string>();
                            var skeletons = new List<PointF[]>();
                            foreach (var hand in array.EnumerateArray())
                            {
                                var label = RequiredString(hand, "handedness");
                                if (label is not ("Left" or "Right")) throw new InvalidDataException("Неизвестная рука");
                                var points = hand.GetProperty("landmarks");
                                if (points.ValueKind != JsonValueKind.Array || points.GetArrayLength() != 21) throw new InvalidDataException("Ожидалось 21 точка на руку");
                                var wrist = points[0];
                                var skeleton = new PointF[21];
                                var pointIndex = 0;
                                descriptions.Add($"{label}: запястье ({Finite(wrist, "x"):F2}, {Finite(wrist, "y"):F2}, {Finite(wrist, "z"):F2})");
                                foreach (var point in points.EnumerateArray()) { var px = Finite(point, "x"); var py = Finite(point, "y"); _ = Finite(point, "z"); if (px is < 0f or > 1f || py is < 0f or > 1f) throw new InvalidDataException("Координаты landmarks должны быть в [0,1]"); skeleton[pointIndex++] = new PointF(px, py); }
                                skeletons.Add(skeleton);
                            }
                            var handsText = handCount == 0 ? "Руки не обнаружены" : string.Join(" | ", descriptions);
                            var skeletonSnapshot = skeletons.ToArray();
                            UpdateUi(() => { _hands.Text = handsText; _handSkeleton = skeletonSnapshot; _camera.Invalidate(); });
                            break;
                        }
                        default: throw new InvalidDataException($"Неизвестный пакет {type} v{version}");
                    }
                }
                catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException or InvalidDataException or FormatException or OverflowException)
                {
                    Log($"Отклонён пакет от {packet.RemoteEndPoint}: {ex.Message}");
                }
            }
        }
        catch (ObjectDisposedException) when (token.IsCancellationRequested) { }
        catch (SocketException) when (token.IsCancellationRequested) { }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception ex) { Log("Ошибка UDP: " + ex.Message); UpdateUi(() => _status.Text = "Ошибка приёмника"); }
    }

    private void DrawHandSkeleton(object? sender, PaintEventArgs e)
    {
        var frame = _camera.Image;
        if (frame is null) return;
        var scale = Math.Min(_camera.ClientSize.Width / (float)frame.Width, _camera.ClientSize.Height / (float)frame.Height);
        var drawWidth = frame.Width * scale;
        var drawHeight = frame.Height * scale;
        var offsetX = (_camera.ClientSize.Width - drawWidth) / 2f;
        var offsetY = (_camera.ClientSize.Height - drawHeight) / 2f;
        PointF Map(PointF p) => new(offsetX + p.X * drawWidth, offsetY + p.Y * drawHeight);
        e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using var bonePen = new Pen(Color.FromArgb(235, 73, 226, 178), Math.Max(2f, scale * 3f)) { StartCap = System.Drawing.Drawing2D.LineCap.Round, EndCap = System.Drawing.Drawing2D.LineCap.Round };
        using var jointBrush = new SolidBrush(Color.White);
        foreach (var hand in _handSkeleton)
        {
            foreach (var (a, b) in HandBones) e.Graphics.DrawLine(bonePen, Map(hand[a]), Map(hand[b]));
            foreach (var point in hand) { var p = Map(point); var r = Math.Max(2.5f, scale * 4f); e.Graphics.FillEllipse(jointBrush, p.X-r, p.Y-r, r*2, r*2); }
        }
        using var bodyPen = new Pen(Color.FromArgb(230, 255, 190, 70), Math.Max(2f, scale * 4f)) { StartCap = System.Drawing.Drawing2D.LineCap.Round, EndCap = System.Drawing.Drawing2D.LineCap.Round };
        if (_bodySkeleton.Length == 33)
        {
            foreach (var (a, b) in BodyBones) e.Graphics.DrawLine(bodyPen, Map(_bodySkeleton[a]), Map(_bodySkeleton[b]));
            foreach (var point in _bodySkeleton) { var p = Map(point); var r = Math.Max(2.5f, scale * 4f); e.Graphics.FillEllipse(Brushes.Orange, p.X-r, p.Y-r, r*2, r*2); }
        }
    }

    private static string RequiredString(JsonElement element, string key) =>
        element.GetProperty(key).GetString() ?? throw new InvalidDataException($"{key} пуст");
    private static float Finite(JsonElement element, string key)
    {
        var value = element.GetProperty(key).GetSingle();
        return float.IsFinite(value) ? value : throw new InvalidDataException($"{key} не является конечным числом");
    }

    private void UpdateUi(Action action)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(action); } catch (InvalidOperationException) { }
    }

    private void Log(string message)
    {
        UpdateUi(() =>
        {
            _log.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
            if (_log.Lines.Length > 500) _log.Lines = _log.Lines[^400..];
        });
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _stop?.Cancel();
        _tcpListener?.Stop();
        _pcCameraStop?.Cancel();
        _camera.Image?.Dispose();
        _pcCamera.Image?.Dispose();
        _driverRelay.Dispose();
        base.OnFormClosing(e);
    }
}
