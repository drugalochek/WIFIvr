using System.Buffers.Binary;
using System.Drawing;
using System.Windows.Forms;
using System.Net;
using System.Net.Sockets;
using System.Numerics;
using System.Text.Json;

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
    private long _cameraFrames;

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

        var cards = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2, RowCount = 2, Padding = new Padding(0, 0, 0, 10) };
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        cards.Controls.Add(MakeCard("СОСТОЯНИЕ", _status), 0, 0);
        cards.Controls.Add(MakeCard("ОРИЕНТАЦИЯ ГОЛОВЫ", _head), 1, 0);
        cards.Controls.Add(MakeCard("РУКИ / LANDMARKS", _hands), 0, 1);
        cards.Controls.Add(MakeCard("АДРЕСА И ПОРТЫ", _endpoint), 1, 1);
        root.Controls.Add(cards);

        var cameraPanel = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1, BackColor = Color.FromArgb(27, 39, 64), Padding = new Padding(10) };
        cameraPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        cameraPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var cameraHeader = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        cameraHeader.Controls.Add(new Label { Text = "КАМЕРА ТЕЛЕФОНА", AutoSize = true, ForeColor = Color.FromArgb(145, 171, 199), Font = new Font("Segoe UI", 9, FontStyle.Bold), Padding = new Padding(0, 6, 12, 0) });
        cameraHeader.Controls.Add(_cameraStatus);
        cameraPanel.Controls.Add(cameraHeader, 0, 0);
        cameraPanel.Controls.Add(_camera, 0, 1);
        root.Controls.Add(cameraPanel);

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
        actions.Controls.Add(new Label { Text = "Порты: UDP 9000 (датчики) · TCP 9001 (видео камеры)", AutoSize = true, ForeColor = Color.LightSteelBlue, Padding = new Padding(10, 8, 0, 0) });
        bottom.Controls.Add(actions, 0, 0);
        bottom.Controls.Add(_log, 0, 1);
        root.Controls.Add(bottom);
        Log("VRoom Desktop готов. Запусти приём, затем подключи телефон к одному Wi-Fi.");
    }

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
            _toggle.Enabled = false;
            if (_listener is not null) { try { await _listener; } catch { } }
            if (_cameraListener is not null) { try { await _cameraListener; } catch { } }
            _stop.Dispose(); _stop = null; _listener = null; _cameraListener = null; _tcpListener = null;
            _toggle.Enabled = true; _toggle.Text = "Запустить VRoom";
            _status.Text = "Остановлено";
            _cameraStatus.Text = "Ожидание телефона · TCP 9001";
            Log("Сетевые службы остановлены.");
            return;
        }

        _stop = new CancellationTokenSource();
        _toggle.Text = "Остановить VRoom";
        _status.Text = "Службы запущены";
        _endpoint.Text = string.Join(", ", GetLocalAddresses()) + " · UDP 9000 · TCP 9001";
        _listener = ListenTrackingAsync(_stop.Token);
        _tcpListener = new TcpListener(IPAddress.Any, 9001);
        _cameraListener = ListenCameraAsync(_stop.Token);
        Log("UDP 9000 и TCP 9001 запущены.");
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
                            break;
                        }
                        case ("hand_pose", 1):
                        {
                            var array = root.GetProperty("hands");
                            if (array.ValueKind != JsonValueKind.Array || array.GetArrayLength() > 2) throw new InvalidDataException("Некорректный список рук");
                            var descriptions = new List<string>();
                            foreach (var hand in array.EnumerateArray())
                            {
                                var label = RequiredString(hand, "handedness");
                                if (label is not ("Left" or "Right")) throw new InvalidDataException("Неизвестная рука");
                                var points = hand.GetProperty("landmarks");
                                if (points.ValueKind != JsonValueKind.Array || points.GetArrayLength() != 21) throw new InvalidDataException("Ожидалось 21 точка на руку");
                                var wrist = points[0];
                                descriptions.Add($"{label}: запястье ({Finite(wrist, "x"):F2}, {Finite(wrist, "y"):F2}, {Finite(wrist, "z"):F2})");
                                foreach (var point in points.EnumerateArray()) { _ = Finite(point, "x"); _ = Finite(point, "y"); _ = Finite(point, "z"); }
                            }
                            UpdateUi(() => _hands.Text = array.GetArrayLength() == 0 ? "Руки не обнаружены" : string.Join(" | ", descriptions));
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
        _camera.Image?.Dispose();
        base.OnFormClosing(e);
    }
}
