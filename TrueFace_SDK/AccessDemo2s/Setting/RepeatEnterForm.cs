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
    public partial class RepeatEnterForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private NET_CFG_OPEN_DOOR_ROUTE_INFO cfg = new NET_CFG_OPEN_DOOR_ROUTE_INFO();
        private int m_Channel = 0;

        private const string CFG_CMD_OPEN_DOOR_ROUTE = "OpenDoorRoute";

        public RepeatEnterForm()
        {
            InitializeComponent();
        }

        public RepeatEnterForm(IntPtr loginid, int channel)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_Channel = channel;
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            UIClear();
            if (GetConfig())
            {
                MessageBox.Show("Get successfully()");

                if (cfg.nDoorList >= 1)
                {
                    txt_Time1.Text = cfg.stuDoorList[0].nResetTime.ToString();
                    if (cfg.stuDoorList[0].nDoors >= 1)
                    {
                        txt_ID11.Text = cfg.stuDoorList[0].stuDoors[0].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 2)
                    {
                        txt_ID12.Text = cfg.stuDoorList[0].stuDoors[1].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 3)
                    {
                        txt_ID13.Text = cfg.stuDoorList[0].stuDoors[2].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 4)
                    {
                        txt_ID14.Text = cfg.stuDoorList[0].stuDoors[3].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 5)
                    {
                        txt_ID15.Text = cfg.stuDoorList[0].stuDoors[4].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 6)
                    {
                        txt_ID16.Text = cfg.stuDoorList[0].stuDoors[5].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 7)
                    {
                        txt_ID17.Text = cfg.stuDoorList[0].stuDoors[6].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 8)
                    {
                        txt_ID18.Text = cfg.stuDoorList[0].stuDoors[7].szReaderID;
                    }
                }
                if (cfg.nDoorList >= 2)
                {
                    txt_Time2.Text = cfg.stuDoorList[1].nResetTime.ToString();
                    if (cfg.stuDoorList[1].nDoors >= 1)
                    {
                        txt_ID21.Text = cfg.stuDoorList[1].stuDoors[0].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 2)
                    {
                        txt_ID22.Text = cfg.stuDoorList[1].stuDoors[1].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 3)
                    {
                        txt_ID23.Text = cfg.stuDoorList[1].stuDoors[2].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 4)
                    {
                        txt_ID24.Text = cfg.stuDoorList[1].stuDoors[3].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 5)
                    {
                        txt_ID25.Text = cfg.stuDoorList[1].stuDoors[4].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 6)
                    {
                        txt_ID26.Text = cfg.stuDoorList[1].stuDoors[5].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 7)
                    {
                        txt_ID27.Text = cfg.stuDoorList[1].stuDoors[6].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 8)
                    {
                        txt_ID28.Text = cfg.stuDoorList[1].stuDoors[7].szReaderID;
                    }
                }
            }
        }

        private void btn_Set_Click(object sender, EventArgs e)
        {
            try
            {
                cfg.stuDoorList[0].nDoors = 0;
                cfg.stuDoorList[0].stuDoors[0].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[1].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[2].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[3].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[4].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[5].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[6].szReaderID = "";
                cfg.stuDoorList[0].stuDoors[7].szReaderID = "";

                cfg.stuDoorList[1].nDoors = 0;
                cfg.stuDoorList[1].stuDoors[0].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[1].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[2].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[3].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[4].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[5].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[6].szReaderID = "";
                cfg.stuDoorList[1].stuDoors[7].szReaderID = "";

                if (!string.IsNullOrEmpty(txt_ID11.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 1;
                    cfg.stuDoorList[0].stuDoors[0].szReaderID = txt_ID11.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID12.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 2;
                    cfg.stuDoorList[0].stuDoors[1].szReaderID = txt_ID12.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID13.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 3;
                    cfg.stuDoorList[0].stuDoors[2].szReaderID = txt_ID13.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID14.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 4;
                    cfg.stuDoorList[0].stuDoors[3].szReaderID = txt_ID14.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID15.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 5;
                    cfg.stuDoorList[0].stuDoors[4].szReaderID = txt_ID15.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID16.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 6;
                    cfg.stuDoorList[0].stuDoors[5].szReaderID = txt_ID16.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID17.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 7;
                    cfg.stuDoorList[0].stuDoors[6].szReaderID = txt_ID17.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID18.Text.Trim()))
                {
                    cfg.stuDoorList[0].nDoors = 8;
                    cfg.stuDoorList[0].stuDoors[7].szReaderID = txt_ID18.Text.Trim();
                }
                if (cfg.stuDoorList[0].nDoors > 0)
                {
                    cfg.nDoorList = 1;
                }
                cfg.stuDoorList[0].nResetTime = uint.Parse(txt_Time1.Text.Trim());

                if (!string.IsNullOrEmpty(txt_ID21.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 1;
                    cfg.stuDoorList[1].stuDoors[0].szReaderID = txt_ID21.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID22.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 2;
                    cfg.stuDoorList[1].stuDoors[1].szReaderID = txt_ID22.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID23.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 3;
                    cfg.stuDoorList[1].stuDoors[2].szReaderID = txt_ID23.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID24.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 4;
                    cfg.stuDoorList[1].stuDoors[3].szReaderID = txt_ID24.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID25.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 5;
                    cfg.stuDoorList[1].stuDoors[4].szReaderID = txt_ID25.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID26.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 6;
                    cfg.stuDoorList[1].stuDoors[5].szReaderID = txt_ID26.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID27.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 7;
                    cfg.stuDoorList[1].stuDoors[6].szReaderID = txt_ID27.Text.Trim();
                }
                if (!string.IsNullOrEmpty(txt_ID28.Text.Trim()))
                {
                    cfg.stuDoorList[1].nDoors = 8;
                    cfg.stuDoorList[1].stuDoors[7].szReaderID = txt_ID28.Text.Trim();
                }
                if (cfg.stuDoorList[1].nDoors > 0)
                {
                    cfg.nDoorList = 2;
                }
                cfg.stuDoorList[1].nResetTime = uint.Parse(txt_Time2.Text.Trim());
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                return;
            }

            if (SetConfig(cfg))
            {
                MessageBox.Show("Set successfully()");
            }
        }

        public bool GetConfig()
        {
            bool bRet = false;
            try
            {
                object objTemp = new object();
                bRet = NETClient.GetNewDevConfig(m_LoginID, -1, CFG_CMD_OPEN_DOOR_ROUTE, ref objTemp, typeof(NET_CFG_OPEN_DOOR_ROUTE_INFO), 5000);
                if (bRet)
                {
                    cfg = (NET_CFG_OPEN_DOOR_ROUTE_INFO)objTemp;
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
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

        public bool SetConfig(NET_CFG_OPEN_DOOR_ROUTE_INFO cfg)
        {
            bool bRet = false;
            try
            {
                for (int i = 0; i < m_Channel; i++)
                {
                    bRet = NETClient.SetNewDevConfig(m_LoginID, i, CFG_CMD_OPEN_DOOR_ROUTE, (object)cfg, typeof(NET_CFG_OPEN_DOOR_ROUTE_INFO), 5000);
                    if (!bRet)
                    {
                        MessageBox.Show(NETClient.GetLastError());
                        return false;
                    }
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

        public void UIClear()
        {
            txt_Time1.Text = "";
            txt_Time2.Text = "";
            txt_ID11.Text = "";
            txt_ID12.Text = "";
            txt_ID13.Text = "";
            txt_ID14.Text = "";
            txt_ID15.Text = "";
            txt_ID16.Text = "";
            txt_ID17.Text = "";
            txt_ID18.Text = "";
            txt_ID21.Text = "";
            txt_ID22.Text = "";
            txt_ID23.Text = "";
            txt_ID24.Text = "";
            txt_ID25.Text = "";
            txt_ID26.Text = "";
            txt_ID27.Text = "";
            txt_ID28.Text = "";
        }

        private void RepeatEnterForm_Load(object sender, EventArgs e)
        {
            UIClear();
            if (GetConfig())
            {
                if (cfg.nDoorList >= 1)
                {
                    txt_Time1.Text = cfg.stuDoorList[0].nResetTime.ToString();
                    if (cfg.stuDoorList[0].nDoors >= 1)
                    {
                        txt_ID11.Text = cfg.stuDoorList[0].stuDoors[0].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 2)
                    {
                        txt_ID12.Text = cfg.stuDoorList[0].stuDoors[1].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 3)
                    {
                        txt_ID13.Text = cfg.stuDoorList[0].stuDoors[2].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 4)
                    {
                        txt_ID14.Text = cfg.stuDoorList[0].stuDoors[3].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 5)
                    {
                        txt_ID15.Text = cfg.stuDoorList[0].stuDoors[4].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 6)
                    {
                        txt_ID16.Text = cfg.stuDoorList[0].stuDoors[5].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 7)
                    {
                        txt_ID17.Text = cfg.stuDoorList[0].stuDoors[6].szReaderID;
                    }
                    if (cfg.stuDoorList[0].nDoors >= 8)
                    {
                        txt_ID18.Text = cfg.stuDoorList[0].stuDoors[7].szReaderID;
                    }
                }
                if (cfg.nDoorList >= 2)
                {
                    txt_Time2.Text = cfg.stuDoorList[1].nResetTime.ToString();
                    if (cfg.stuDoorList[1].nDoors >= 1)
                    {
                        txt_ID21.Text = cfg.stuDoorList[1].stuDoors[0].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 2)
                    {
                        txt_ID22.Text = cfg.stuDoorList[1].stuDoors[1].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 3)
                    {
                        txt_ID23.Text = cfg.stuDoorList[1].stuDoors[2].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 4)
                    {
                        txt_ID24.Text = cfg.stuDoorList[1].stuDoors[3].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 5)
                    {
                        txt_ID25.Text = cfg.stuDoorList[1].stuDoors[4].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 6)
                    {
                        txt_ID26.Text = cfg.stuDoorList[1].stuDoors[5].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 7)
                    {
                        txt_ID27.Text = cfg.stuDoorList[1].stuDoors[6].szReaderID;
                    }
                    if (cfg.stuDoorList[1].nDoors >= 8)
                    {
                        txt_ID28.Text = cfg.stuDoorList[1].stuDoors[7].szReaderID;
                    }
                }
            }
        }
    }
}
