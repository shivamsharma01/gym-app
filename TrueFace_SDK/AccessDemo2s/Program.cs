using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    static class Program
    {
        /// <summary>
        /// 。
        /// </summary>
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            Application.OpenForms.Cast<Form>().ToList().ForEach(form => Common.Common.ChangeFont(form, new Font("Verdana", 9)));

            Application.Run(new AccessForm());
        }
    }
}
