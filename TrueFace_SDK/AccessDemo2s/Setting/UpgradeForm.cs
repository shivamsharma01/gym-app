using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class UpgradeForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private IntPtr m_UpgradeID = IntPtr.Zero;

        private static fUpgradeCallBack m_UpgradeCallBack;

        public UpgradeForm()
        {
            InitializeComponent();
        }

        public UpgradeForm(IntPtr id)
        {
            InitializeComponent();
            m_LoginID = id;
            m_UpgradeCallBack = new fUpgradeCallBack(UpgradeCallBack);
        }

        private void UpgradeCallBack(IntPtr lLoginID, IntPtr lUpgradechannel, int nTotalSize, int nSendSize, IntPtr dwUser)
        {
            try
            {
                if (lLoginID != null && lUpgradechannel != null)
                {
                    this.BeginInvoke(new Action(() =>
                    {
                        if (nTotalSize == 0)
                        {
                            if (nSendSize == -1)
                            {
                                textBox_UpgrageState.Text = DateTime.Now.ToString("HH:mm:ss") + "Upgrade succeed()";
                                button_Upgrade.Enabled = true;
                                button_StopUpgrade.Enabled = false;
                                progressBar_Upgrade.Value = 100;
                            }
                            else if (nSendSize == -2)
                            {
                                textBox_UpgrageState.Text = DateTime.Now.ToString("HH:mm:ss") + "Upgrade failed()";
                                button_Upgrade.Enabled = true;
                                button_StopUpgrade.Enabled = false;
                                progressBar_Upgrade.Value = 0;
                            }
                            if (IntPtr.Zero == m_UpgradeID)
                            {
                                return;
                            }
                            try
                            {
                                NETClient.StopUpgrade(m_UpgradeID);
                                m_UpgradeID = IntPtr.Zero;
                            }
                            catch (Exception)
                            {
                            }
                        }
                        else if (nTotalSize > 0)
                        {
                            double dProgress = 0;
                            dProgress = nSendSize / (double)nTotalSize * 100;
                            dProgress = (int)dProgress;
                            if (dProgress <= 0)
                            {
                                dProgress = 0;
                            }
                            if (dProgress > 99)
                            {
                                dProgress = 100;
                            }
                            progressBar_Upgrade.Value = (int)dProgress;
                            textBox_UpgrageState.Text = DateTime.Now.ToString("HH:mm:ss") + "Upgrade Progress()：" + dProgress.ToString() + "%" + nSendSize.ToString() + "/" + nTotalSize.ToString();
                        }
                    }));
                }
            }
            catch (Exception)
            {
                
            }
        }

        private void button_OpenFile_Click(object sender, EventArgs e)
        {
            OpenFileDialog openFileDialog = new OpenFileDialog();
            openFileDialog.Filter = "(*.*)|*.*";
            var ret = openFileDialog.ShowDialog();
            if (ret == DialogResult.OK)
            {
                try
                {
                    string path = openFileDialog.FileName;
                    textBox_Path.Text = openFileDialog.FileName;
                    button_Upgrade.Enabled = true;
                    button_StopUpgrade.Enabled = false;
                }
                catch (Exception ex)
                {
                    Console.WriteLine(ex);
                }
            }
            openFileDialog.Dispose();
        }

        private void button_Upgrade_Click(object sender, EventArgs e)
        {
            button_Upgrade.Enabled = false;
            button_StopUpgrade.Enabled = true;
            progressBar_Upgrade.Value = 0;

            if (IntPtr.Zero != m_UpgradeID)
            {
                try
                {
                    NETClient.StopUpgrade(m_UpgradeID);
                    m_UpgradeID = IntPtr.Zero;
                }
                catch (Exception)
                {
                }
            }
            
            #region upgrade device 
            if (textBox_Path.Text == null || textBox_Path.Text == "")
            {
                MessageBox.Show("please choose a upgrade packet file.()");
                return;
            }

            m_UpgradeID = NETClient.StartUpgrade(m_LoginID, EM_UPGRADE_TYPE.BIOS_TYPE, textBox_Path.Text, m_UpgradeCallBack, IntPtr.Zero);
            if (IntPtr.Zero != m_UpgradeID)
            {
                bool bRet = NETClient.SendUpgrade(m_UpgradeID);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    button_Upgrade.Enabled = true;
                    button_StopUpgrade.Enabled = false;
                }
            }
            else
            {
                MessageBox.Show(NETClient.GetLastError());
                button_Upgrade.Enabled = true;
                button_StopUpgrade.Enabled = false;
            }
            #endregion
        }

        private void button_StopUpgrade_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_UpgradeID)
            {
                return;
            }
            bool ret = NETClient.StopUpgrade(m_UpgradeID);
            if (ret)
            {
                m_UpgradeID = IntPtr.Zero;
                button_Upgrade.Enabled = true;
                button_StopUpgrade.Enabled = false;
                progressBar_Upgrade.Value = 0;
            }
            else
            {
                MessageBox.Show(NETClient.GetLastError());
            }
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (IntPtr.Zero == m_UpgradeID)
            {
                return;
            }
            try
            {
                NETClient.StopUpgrade(m_UpgradeID);
                m_UpgradeID = IntPtr.Zero;
            }
            catch (Exception)
            {
            }

            base.OnClosing(e);
        }
    }
}
