using System.Net.Sockets;
using System.Text;
using System.Text.Json;

const int Port = 9000;
using var udp = new UdpClient(Port);

Console.WriteLine($"WiFi VR head-pose receiver listening on UDP :{Port}");
Console.WriteLine("Keep the phone and PC on the same trusted Wi-Fi network.");

while (true)
{
    var packet = await udp.ReceiveAsync();
    try
    {
        using var json = JsonDocument.Parse(Encoding.UTF8.GetString(packet.Buffer));
        var root = json.RootElement;

        if (root.GetProperty("type").GetString() != "head_pose" ||
            root.GetProperty("version").GetInt32() != 1)
            continue;

        var q = root.GetProperty("orientation");
        double x = q.GetProperty("x").GetDouble();
        double y = q.GetProperty("y").GetDouble();
        double z = q.GetProperty("z").GetDouble();
        double w = q.GetProperty("w").GetDouble();

        Console.Write($"\r{packet.RemoteEndPoint}  q=({x:F3}, {y:F3}, {z:F3}, {w:F3})     ");
    }
    catch (JsonException)
    {
        Console.WriteLine("\nIgnored malformed JSON packet.");
    }
    catch (KeyNotFoundException)
    {
        Console.WriteLine("\nIgnored packet with missing fields.");
    }
    catch (InvalidOperationException)
    {
        Console.WriteLine("\nIgnored packet with invalid field types.");
    }
}
