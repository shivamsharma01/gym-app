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
    public partial class NetConfigForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private CFG_NETWORK_INFO cfg = new CFG_NETWORK_INFO();
        private NET_CFG_DVRIP_INFO cfg_Dvrip = new NET_CFG_DVRIP_INFO();

        public NetConfigForm()
        {
            InitializeComponent();
        }

        public NetConfigForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            #region Get network config 
            if (GetConfig_Network())
            {
                for (int i = 0; i < cfg.nInterfaceNum; i++)
                {
                    if (string.Equals(cfg.szDefInterface, Encoding.Default.GetString(cfg.stuInterfaces[i].szName)))
                    {
                        if (Encoding.Default.GetString(cfg.stuInterfaces[i].szIP)[0] == 0
                            || Encoding.Default.GetString(cfg.stuInterfaces[i].szSubnetMask)[0] == 0
                            || Encoding.Default.GetString(cfg.stuInterfaces[i].szDefGateway)[0] == 0)
                        {
                            return;
                        }
                        else
                        {
                            this.txt_ip.Text = Encoding.Default.GetString(cfg.stuInterfaces[i].szIP);
                            this.txt_mask.Text = Encoding.Default.GetString(cfg.stuInterfaces[i].szSubnetMask);
                            this.txt_gateway.Text = Encoding.Default.GetString(cfg.stuInterfaces[i].szDefGateway);
                        }

                    }
                }
            }
            #endregion
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            #region Set network config 
            if (txt_ip.Text == null || txt_ip.Text == "")
            {
                MessageBox.Show("Please input IP address(IP)");
                return;
            }
            if (txt_mask.Text == null || txt_mask.Text == "")
            {
                MessageBox.Show("Please input Mask()");
                return;
            }
            if (txt_gateway.Text == null || txt_gateway.Text == "")
            {
                MessageBox.Show("Please input Gate Way()");
                return;
            }
            for (int i = 0; i < cfg.nInterfaceNum; i++)
            {
                if (string.Equals("eth0", Encoding.Default.GetString(cfg.stuInterfaces[i].szName).Trim('\0')))
                {
                    cfg.stuInterfaces[i].szIP = new byte[256];
                    cfg.stuInterfaces[i].szSubnetMask = new byte[256];
                    cfg.stuInterfaces[i].szDefGateway = new byte[256];
                    Encoding.Default.GetBytes(txt_ip.Text.Trim(), 0, txt_ip.Text.Trim().Length, cfg.stuInterfaces[i].szIP, 0);
                    Encoding.Default.GetBytes(txt_mask.Text.Trim(), 0, txt_mask.Text.Trim().Length, cfg.stuInterfaces[i].szSubnetMask, 0);
                    Encoding.Default.GetBytes(txt_gateway.Text.Trim(), 0, txt_gateway.Text.Trim().Length, cfg.stuInterfaces[i].szDefGateway, 0);
                }
            }

            bool bRet = SetConfig_Network(cfg);
            if (!bRet)
            {
                return;
            }
            MessageBox.Show("Set Success()");

            #endregion
        }

        private void btn_AutoGet_Click(object sender, EventArgs e)
        {
            if (GetConfig_Dvrip())
            {
                chb_enable.Checked = cfg_Dvrip.stuRegister[0].bEnable;
                this.txt_deviceid.Text = cfg_Dvrip.stuRegister[0].szDeviceID;
                this.txt_port.Text = cfg_Dvrip.stuRegister[0].stuServers[0].nPort.ToString();
                this.txt_deviceip.Text = cfg_Dvrip.stuRegister[0].stuServers[0].szAddress;
            }
        }

        private void btn_AutoSet_Click(object sender, EventArgs e)
        {
            #region Set Dvrip config 
            if (txt_deviceip.Text == null || txt_deviceip.Text == "")
            {
                MessageBox.Show("Please input register IP(IP)");
                return;
            }
            if (txt_deviceid.Text == null || txt_deviceid.Text == "")
            {
                MessageBox.Show("Please input device ID(ID)");
                return;
            }
            if (txt_port.Text == null || txt_port.Text == "")
            {
                MessageBox.Show("Please input register Port()");
                return;
            }
            ushort port;
            try
            {
                port = Convert.ToUInt16(txt_port.Text.Trim());
            }
            catch
            {
                MessageBox.Show("The register port is error,the value must be 1-65535(，1-65535)");
                return;
            }
            cfg_Dvrip.nRegistersNum = 1;
            cfg_Dvrip.stuRegister[0].szDeviceID = txt_deviceid.Text.Trim();
            cfg_Dvrip.stuRegister[0].stuServers[0].szAddress = txt_deviceip.Text.Trim();
            cfg_Dvrip.stuRegister[0].stuServers[0].nPort = port;
            cfg_Dvrip.stuRegister[0].bEnable = this.chb_enable.Checked;
            cfg_Dvrip.stuRegister[0].nServersNum = 1;
            bool bRet = SetConfig_Dvrip(cfg_Dvrip);
            if (!bRet)
            {
                return;
            }
            MessageBox.Show("Set Success()");
            #endregion
        }

        public bool GetConfig_Network()
        {
            try
            {
                object objTemp = new object();
                bool bRet = NETClient.GetNewDevConfig(m_LoginID, -1, "Network", ref objTemp, typeof(CFG_NETWORK_INFO), 5000);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return false;
                }
                cfg = (CFG_NETWORK_INFO)objTemp;
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
                return false;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return false;
            }
            return true;
        }


        public bool SetConfig_Network(CFG_NETWORK_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, -1, "Network", (object)cfg, typeof(CFG_NETWORK_INFO), 5000);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return bRet;
                }
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return bRet;
        }

        public bool GetConfig_Dvrip()
        {
            try
            {
                object objTemp = new object();
                objTemp = (object)cfg_Dvrip;
                bool bRet = NETClient.GetNewDevConfig(m_LoginID, -1, "DVRIP", ref objTemp, typeof(NET_CFG_DVRIP_INFO), 5000);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return false;
                }
                cfg_Dvrip = (NET_CFG_DVRIP_INFO)objTemp;
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
                return false;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return false;
            }
            return true;
        }


        public bool SetConfig_Dvrip(NET_CFG_DVRIP_INFO cfg_Dvrip)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, -1, "DVRIP", (object)cfg_Dvrip, typeof(NET_CFG_DVRIP_INFO), 5000);
                if (!bRet)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return bRet;
                }
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return bRet;
        }

        private void NetConfigForm_Load(object sender, EventArgs e)
        {
            if (GetConfig_Network())
            {
                for (int i = 0; i < cfg.nInterfaceNum; i++)
                {
                    if (string.Equals(cfg.szDefInterface, Encoding.Default.GetString(cfg.stuInterfaces[i].szName)))
                    {
                        if (Encoding.Default.GetString(cfg.stuInterfaces[i].szIP)[0] == 0
                            || Encoding.Default.GetString(cfg.stuInterfaces[i].szSubnetMask)[0] == 0
                            || Encoding.Default.GetString(cfg.stuInterfaces[i].szDefGateway)[0] == 0)
                        {
                            return;
                        }
                        else
                        {
                            this.txt_ip.Text = Encoding.Default.GetString(cfg.stuInterfaces[i].szIP);
                            this.txt_mask.Text = Encoding.Default.GetString(cfg.stuInterfaces[i].szSubnetMask);
                            this.txt_gateway.Text = Encoding.Default.GetString(cfg.stuInterfaces[i].szDefGateway);
                        }

                    }
                }

                if (GetConfig_Dvrip())
                {
                    chb_enable.Checked = cfg_Dvrip.stuRegister[0].bEnable;
                    this.txt_deviceid.Text = cfg_Dvrip.stuRegister[0].szDeviceID;
                    this.txt_port.Text = cfg_Dvrip.stuRegister[0].stuServers[0].nPort.ToString();
                    this.txt_deviceip.Text = cfg_Dvrip.stuRegister[0].stuServers[0].szAddress;
                }
            }
        }
    }
}
