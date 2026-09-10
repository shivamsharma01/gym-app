using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class AutoMatrixForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private NET_DEV_AUTOMT_CFG cfg_AutoMT = new NET_DEV_AUTOMT_CFG();

        public AutoMatrixForm()
        {
            InitializeComponent();
        }

        public AutoMatrixForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void button_GetAutoMatrix_Click(object sender, EventArgs e)
        {
            comboBox_RebootDay.SelectedIndex = 0;
            comboBox_RebootTime.SelectedIndex = 0;
            #region Get auto matrix config 
            if (GetDevConfig_AutoMT())
            {
                //MessageBox.Show("Get successfully.()");
                comboBox_RebootDay.SelectedIndex = cfg_AutoMT.byAutoRebootDay;
                comboBox_RebootTime.SelectedIndex = cfg_AutoMT.byAutoRebootTime;
            }
            #endregion
        }

        private void button_SetAutoMatrix_Click(object sender, EventArgs e)
        {
            #region Set auto matrix config 
            cfg_AutoMT.byAutoRebootDay = (byte)comboBox_RebootDay.SelectedIndex;
            cfg_AutoMT.byAutoRebootTime = (byte)comboBox_RebootTime.SelectedIndex;
            IntPtr inPtr = IntPtr.Zero;
            inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_DEV_AUTOMT_CFG)));
            Marshal.StructureToPtr(cfg_AutoMT, inPtr, true);

            try
            {
                bool result = NETClient.SetDevConfig(m_LoginID, EM_DEV_CFG_TYPE.AUTOMTCFG, 0, inPtr, (uint)Marshal.SizeOf(typeof(NET_DEV_AUTOMT_CFG)), 5000);
                if (!result)
                {
                    MessageBox.Show(NETClient.GetLastError());
                }
                else
                {
                    //MessageBox.Show("Set successfully.()");
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(inPtr);
            }
            #endregion
        }

        public bool GetDevConfig_AutoMT()
        {
            uint ret = 0;
            IntPtr inPtr = IntPtr.Zero;
            try
            {
                inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_DEV_AUTOMT_CFG)));
                Marshal.StructureToPtr(cfg_AutoMT, inPtr, true);
                bool result = NETClient.GetDevConfig(m_LoginID, EM_DEV_CFG_TYPE.AUTOMTCFG, 0, inPtr, (uint)Marshal.SizeOf(typeof(NET_DEV_AUTOMT_CFG)), ref ret, 5000);
                if (result && ret == (uint)Marshal.SizeOf(typeof(NET_DEV_AUTOMT_CFG)))
                {
                    cfg_AutoMT = (NET_DEV_AUTOMT_CFG)Marshal.PtrToStructure(inPtr, typeof(NET_DEV_AUTOMT_CFG));
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return false;
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return false;
            }
            finally
            {
                Marshal.FreeHGlobal(inPtr);
            }
            return true;
        }

        private void AutoMatrixForm_Load(object sender, EventArgs e)
        {
            comboBox_RebootDay.SelectedIndex = 0;
            comboBox_RebootTime.SelectedIndex = 0;
            if (GetDevConfig_AutoMT())
            {
                comboBox_RebootDay.SelectedIndex = cfg_AutoMT.byAutoRebootDay;
                comboBox_RebootTime.SelectedIndex = cfg_AutoMT.byAutoRebootTime;
            }
        }
    }
}
