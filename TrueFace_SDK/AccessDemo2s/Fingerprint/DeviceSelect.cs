using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class DeviceSelect : Form
    {
        private bool isSingle_; //
        private IntPtr loginID_;

        public DeviceSelect(IntPtr loginID, bool isSingle)
        {
            InitializeComponent();
            loginID_ = loginID;
            isSingle_ = isSingle;
        }

        private void Button1_Click(object sender, EventArgs e)
        {
            DeviceInfo winDeviceInfo = new DeviceInfo(loginID_, isSingle_, true);
            winDeviceInfo.ShowDialog();
            winDeviceInfo.Dispose();
        }

        private void Button2_Click(object sender, EventArgs e)
        {
            DeviceInfo winDeviceInfo = new DeviceInfo(loginID_, isSingle_, false);
            winDeviceInfo.ShowDialog();
            winDeviceInfo.Dispose();
        }
    }
}
