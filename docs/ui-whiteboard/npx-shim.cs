using System;
using System.Diagnostics;
using System.IO;
using System.Text;

public static class NpxShim {
    static string Quote(string s) {
        return "\"" + s.Replace("\"", "\\\"") + "\"";
    }
    public static int Main(string[] args) {
        string node = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "nodejs", "node.exe");
        if (!File.Exists(node)) node = "node.exe";
        string npxCli = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "nodejs", "node_modules", "npm", "bin", "npx-cli.js");
        if (!File.Exists(npxCli)) {
            npxCli = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "npm", "node_modules", "npm", "bin", "npx-cli.js");
        }
        if (!File.Exists(npxCli)) {
            Console.Error.WriteLine("npx shim: cannot locate npx-cli.js");
            return 1;
        }
        var sb = new StringBuilder();
        sb.Append(Quote(npxCli));
        foreach (var a in args) {
            sb.Append(' ').Append(Quote(a));
        }
        var psi = new ProcessStartInfo();
        psi.FileName = node;
        psi.UseShellExecute = false;
        psi.Arguments = sb.ToString();
        try {
            var p = Process.Start(psi);
            p.WaitForExit();
            return p.ExitCode;
        } catch (Exception ex) {
            Console.Error.WriteLine("npx shim error: " + ex.Message);
            return 1;
        }
    }
}
