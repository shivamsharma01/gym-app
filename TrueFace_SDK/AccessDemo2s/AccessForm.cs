using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using NetSDKCS;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;


namespace AccessDemo2s
{
    public partial class AccessForm : Form
    {
        private static readonly string titleName = "AccessControl2S Demo(Demo)";
        private IntPtr m_LoginID = IntPtr.Zero;
        private const string CFG_CMD_ACCESS_EVENT = "AccessControl";
        private const int ALARM_START = 0;
        private const int ALARM_STOP = 1;
        private IntPtr m_ListenID = IntPtr.Zero;
        private int m_AccessCount = 0;
        public static bool m_IsListen = false;
        private const int m_WaitTime = 5000;
        private static int Alarm_Index = 1;
        private byte[] data;
        private const int ListViewCount = 100; //

        private object m_queueLock = new object();
        private Queue<DEVICE_INFO> m_DeviceQueue = new Queue<DEVICE_INFO>();
        private Thread m_LoginThread;

        private static fDisConnectCallBack m_DisConnectCallBack;//
        private static fHaveReConnectCallBack m_ReConnectCallBack;//
        public static fMessCallBack m_AlarmCallBack; //
        private fServiceCallBack m_ServiceCallBack;

        public AccessForm()
        {
            InitializeComponent();
        }

      
        private void AccessDemo2s_Load(object sender, EventArgs e)
        {
            //Segoe UI, 9pt
            Font newFont = new Font("Verdana", 9, FontStyle.Regular);
            Common.Common.ChangeFont(this, newFont);


            Text = titleName;
            m_DisConnectCallBack = new fDisConnectCallBack(DisConnectCallBack);
            m_ReConnectCallBack = new fHaveReConnectCallBack(ReConnectCallBack);
            m_AlarmCallBack = new fMessCallBack(AlarmCallBack);
            m_ServiceCallBack = new fServiceCallBack(ServiceCallBack);

            ThreadStart ts = new ThreadStart(DeviceLogin);
            m_LoginThread = new Thread(ts);
            m_LoginThread.IsBackground = true;
            m_LoginThread.Start();
            
            
            try
            {
      
                NETClient.Init(m_DisConnectCallBack, IntPtr.Zero, null);
              
                NET_LOG_SET_PRINT_INFO logInfo = new NET_LOG_SET_PRINT_INFO()
                {
                    dwSize = (uint)Marshal.SizeOf(typeof(NET_LOG_SET_PRINT_INFO))
                };
                NETClient.LogOpen(logInfo);
       
                NETClient.SetAutoReconnect(m_ReConnectCallBack, IntPtr.Zero);
                
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
                Process.GetCurrentProcess().Kill();
            }
        }

        #region Update UI

        private void UpdateDisConnectUI()
        {
            this.Text = titleName + " -- Offline()";

            InitOrCloseOtherUI();
        }

        private void UpdateReConnectUI()
        {
            this.Text = titleName + " -- Online()";

            OpenOtherUI();
        }


        private void InitOrLogoutUI()
        {
            this.Text = titleName;
            btn_Login.Text = "Login()";

            InitOrCloseOtherUI();
        }

        private void LoginUI()
        {
            this.Text = titleName + " -- Online()";
            btn_Login.Text = "Logout()";

            OpenOtherUI();
        }

        private void LogoutUIAutoRegister()
        {
            this.Text = titleName;
            button1.Text = "ServerListen()";

            channel_comboBox.Items.Clear();
            btn_OpenDoor.Enabled = false;
            btn_CloseDoor.Enabled = false;
            btn_GetState.Enabled = false;
            btn_OpenAlways.Enabled = false;
            btn_CloseAlways.Enabled = false;

            btn_UserOperate.Enabled = false;
            btn_AccessPassword.Enabled = false;
            btn_GeneralConfig.Enabled = false;

            btn_StartListen.Enabled = false;
            btn_Query.Enabled = false;
            btn_OpenEvent.Enabled = false;

            menu_SystemConfig.Enabled = false;
            menu_AdvanceConfig.Enabled = false;

            groupBox1.Enabled = true;

        }

        private void LoginUIAutoRegister()
        {
            this.Text = titleName + " -- Online()";
            button1.Text = "Logout()";
            channel_comboBox.Items.Clear();
            if (m_AccessCount > 0)
            {
                for (int i = 0; i < m_AccessCount; i++)
                {
                    channel_comboBox.Items.Add(i + 1);
                }
                channel_comboBox.SelectedIndex = 0;
            }

            btn_OpenDoor.Enabled = true;
            btn_CloseDoor.Enabled = true;
            btn_GetState.Enabled = true;
            btn_OpenAlways.Enabled = true;
            btn_CloseAlways.Enabled = true;

            btn_UserOperate.Enabled = true;
            btn_AccessPassword.Enabled = true;
            btn_GeneralConfig.Enabled = true;

            btn_StartListen.Enabled = true;
            btn_Query.Enabled = true;
            btn_OpenEvent.Enabled = true;

            menu_SystemConfig.Enabled = true;
            menu_AdvanceConfig.Enabled = true;

            groupBox1.Enabled = false;
        }

        private void InitOrCloseOtherUI()
        {
         

            channel_comboBox.Items.Clear();
            btn_OpenDoor.Enabled = false;
            btn_CloseDoor.Enabled = false;
            btn_GetState.Enabled = false;
            btn_OpenAlways.Enabled = false;
            btn_CloseAlways.Enabled = false;

            btn_UserOperate.Enabled = false;
            btn_AccessPassword.Enabled = false;
            btn_GeneralConfig.Enabled = false;

            btn_StartListen.Enabled = false;
            btn_Query.Enabled = false;
            btn_OpenEvent.Enabled = false;

            menu_SystemConfig.Enabled = false;
            menu_AdvanceConfig.Enabled = false;

            groupBox6.Enabled = true;

        }

        private void OpenOtherUI()
        {
  

            channel_comboBox.Items.Clear();
            if (m_AccessCount > 0)
            {
                for (int i = 0; i < m_AccessCount; i++)
                {
                    channel_comboBox.Items.Add(i + 1);
                }
                channel_comboBox.SelectedIndex = 0;
            }

            btn_OpenDoor.Enabled = true;
            btn_CloseDoor.Enabled = true;
            btn_GetState.Enabled = true;
            btn_OpenAlways.Enabled = true;
            btn_CloseAlways.Enabled = true;

            btn_UserOperate.Enabled = true;
            btn_AccessPassword.Enabled = true;
            btn_GeneralConfig.Enabled = true;

            btn_StartListen.Enabled = true;
            btn_Query.Enabled = true;
            btn_OpenEvent.Enabled = true;

            menu_SystemConfig.Enabled = true;
            menu_AdvanceConfig.Enabled = true;

            groupBox6.Enabled = false;

        }

        #endregion

        #region CallBack

        private void DisConnectCallBack(IntPtr lLoginID, IntPtr pchDVRIP, int nDVRPort, IntPtr dwUser)
        {
            this.BeginInvoke((Action)UpdateDisConnectUI);
        }

        private void ReConnectCallBack(IntPtr lLoginID, IntPtr pchDVRIP, int nDVRPort, IntPtr dwUser)
        {
            this.BeginInvoke((Action)UpdateReConnectUI);
        }

        private int ServiceCallBack(IntPtr lHandle, IntPtr pIp, ushort wPort, int lCommand, IntPtr pParam, uint dwParamLen, IntPtr dwUserData)
        {
            EM_LISTEN_TYPE type = (EM_LISTEN_TYPE)lCommand;
            string ip = Marshal.PtrToStringAnsi(pIp);
            string id = "";
            if (dwParamLen > 0)
            {
                id = Marshal.PtrToStringAnsi(pParam);
            }
            this.BeginInvoke(new Action<string, ushort, EM_LISTEN_TYPE, string>(UpdateDevice), ip, wPort, type, id);
            return 0;
        }

        private bool AlarmCallBack(int lCommand, IntPtr lLoginID, IntPtr pBuf, uint dwBufLen, IntPtr pchDVRIP, int nDVRPort, IntPtr dwUser)
        {
            EM_ALARM_TYPE type = (EM_ALARM_TYPE)lCommand;
            var item = new ListViewItem();
            switch (type)
            {
                case EM_ALARM_TYPE.ALARM_ACCESS_CTL_EVENT:
                    NET_ALARM_ACCESS_CTL_EVENT_INFO access_info = (NET_ALARM_ACCESS_CTL_EVENT_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_EVENT_INFO));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt1 = new DateTime((int)access_info.stuTime.dwYear, (int)access_info.stuTime.dwMonth, (int)access_info.stuTime.dwDay,
                        (int)access_info.stuTime.dwHour, (int)access_info.stuTime.dwMinute, (int)access_info.stuTime.dwSecond);
                    DateTime retDt1 = Common.Common.GetZoneTimeByUTCTime(dt1);
                    item.SubItems.Add(retDt1.ToString());

                    item.SubItems.Add("Entry)");
                    item.SubItems.Add(access_info.szUserID);
                    item.SubItems.Add(access_info.szCardNo.ToString());
                    item.SubItems.Add(access_info.nDoor.ToString());
                    item.SubItems.Add(AccessDoorOpenMethod2Str(access_info.emOpenMethod));
                    if (access_info.bStatus)
                    {
                        item.SubItems.Add("Success()");
                    }
                    else
                    {
                        item.SubItems.Add("Failure()");
                    }

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_ACCESS_CTL_NOT_CLOSE:
                    NET_ALARM_ACCESS_CTL_NOT_CLOSE_INFO notclose_info = (NET_ALARM_ACCESS_CTL_NOT_CLOSE_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_NOT_CLOSE_INFO));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt2 = new DateTime((int)notclose_info.stuTime.dwYear, (int)notclose_info.stuTime.dwMonth, (int)notclose_info.stuTime.dwDay,
                        (int)notclose_info.stuTime.dwHour, (int)notclose_info.stuTime.dwMinute, (int)notclose_info.stuTime.dwSecond);
                    DateTime retDt2 = Common.Common.GetZoneTimeByUTCTime(dt2);
                    item.SubItems.Add(retDt2.ToString());

                    item.SubItems.Add(dt2.ToString());
                    item.SubItems.Add(notclose_info.stuTime.ToString());
                    item.SubItems.Add("NotClose()");
                    item.SubItems.Add("");
                    item.SubItems.Add("");
                    item.SubItems.Add(notclose_info.nDoor.ToString());
                    item.SubItems.Add("");
                    if (notclose_info.nAction == ALARM_START)
                    {
                        item.SubItems.Add("Start()");
                    }
                    else if (notclose_info.nAction == ALARM_STOP)
                    {
                        item.SubItems.Add("Stop()");
                    }
                    else
                    {
                        item.SubItems.Add("");
                    }

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_ACCESS_CTL_BREAK_IN:
                    NET_ALARM_ACCESS_CTL_BREAK_IN_INFO breakin_info = (NET_ALARM_ACCESS_CTL_BREAK_IN_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_BREAK_IN_INFO));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt3 = new DateTime((int)breakin_info.stuTime.dwYear, (int)breakin_info.stuTime.dwMonth, (int)breakin_info.stuTime.dwDay,
                        (int)breakin_info.stuTime.dwHour, (int)breakin_info.stuTime.dwMinute, (int)breakin_info.stuTime.dwSecond);
                    DateTime retDt3 = Common.Common.GetZoneTimeByUTCTime(dt3);
                    item.SubItems.Add(retDt3.ToString());

                    item.SubItems.Add(dt3.ToString());
                    item.SubItems.Add("BreakIn()");
                    item.SubItems.Add("");
                    item.SubItems.Add("");
                    item.SubItems.Add(breakin_info.nDoor.ToString());
                    item.SubItems.Add("");
                    item.SubItems.Add("");

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_ACCESS_CTL_REPEAT_ENTER:
                    NET_ALARM_ACCESS_CTL_REPEAT_ENTER_INFO repeat_info = (NET_ALARM_ACCESS_CTL_REPEAT_ENTER_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_REPEAT_ENTER_INFO));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt4 = new DateTime((int)repeat_info.stuTime.dwYear, (int)repeat_info.stuTime.dwMonth, (int)repeat_info.stuTime.dwDay,
                        (int)repeat_info.stuTime.dwHour, (int)repeat_info.stuTime.dwMinute, (int)repeat_info.stuTime.dwSecond);
                    DateTime retDt4 = Common.Common.GetZoneTimeByUTCTime(dt4);
                    item.SubItems.Add(retDt4.ToString());
                    item.SubItems.Add("RepeakIn()");
                    item.SubItems.Add("");
                    item.SubItems.Add(repeat_info.szCardNo.ToString());
                    item.SubItems.Add(repeat_info.nDoor.ToString());
                    item.SubItems.Add("");
                    item.SubItems.Add("");

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_ACCESS_CTL_DURESS:
                    NET_ALARM_ACCESS_CTL_DURESS_INFO duress_info = (NET_ALARM_ACCESS_CTL_DURESS_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_DURESS_INFO));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt5 = new DateTime((int)duress_info.stuTime.dwYear, (int)duress_info.stuTime.dwMonth, (int)duress_info.stuTime.dwDay,
                        (int)duress_info.stuTime.dwHour, (int)duress_info.stuTime.dwMinute, (int)duress_info.stuTime.dwSecond);
                    DateTime retDt5 = Common.Common.GetZoneTimeByUTCTime(dt5);
                    item.SubItems.Add(retDt5.ToString());
                    item.SubItems.Add("Duress()");
                    item.SubItems.Add(duress_info.szUserID.ToString());
                    item.SubItems.Add(duress_info.szCardNo.ToString());
                    item.SubItems.Add(duress_info.nDoor.ToString());
                    item.SubItems.Add("");
                    item.SubItems.Add("");

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_CHASSISINTRUDED:
                    NET_ALARM_CHASSISINTRUDED_INFO chassisintruded_info = (NET_ALARM_CHASSISINTRUDED_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_CHASSISINTRUDED_INFO));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt6 = new DateTime((int)chassisintruded_info.stuTime.dwYear, (int)chassisintruded_info.stuTime.dwMonth, (int)chassisintruded_info.stuTime.dwDay,
                        (int)chassisintruded_info.stuTime.dwHour, (int)chassisintruded_info.stuTime.dwMinute, (int)chassisintruded_info.stuTime.dwSecond);
                    DateTime retDt6 = Common.Common.GetZoneTimeByUTCTime(dt6);
                    item.SubItems.Add(retDt6.ToString());
                    if (chassisintruded_info.szReaderID.Length > 0)
                    {
                        item.SubItems.Add("CardreaderAntidemolition()");
                    }
                    else
                    {
                        item.SubItems.Add("ChassisIntruded()");
                    }
                    item.SubItems.Add("");
                    item.SubItems.Add("");
                    item.SubItems.Add(chassisintruded_info.nChannelID.ToString());
                    item.SubItems.Add("");
                    if (chassisintruded_info.nAction == ALARM_START)
                    {
                        item.SubItems.Add("Start()");
                    }
                    else if (chassisintruded_info.nAction == ALARM_STOP)
                    {
                        item.SubItems.Add("Stop()");
                    }
                    else
                    {
                        item.SubItems.Add("");
                    }

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_ALARM_EX2:
                    NET_ALARM_ALARM_INFO_EX2 alarm_info = (NET_ALARM_ALARM_INFO_EX2)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ALARM_INFO_EX2));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt7 = new DateTime((int)alarm_info.stuTime.dwYear, (int)alarm_info.stuTime.dwMonth, (int)alarm_info.stuTime.dwDay,
                        (int)alarm_info.stuTime.dwHour, (int)alarm_info.stuTime.dwMinute, (int)alarm_info.stuTime.dwSecond);
                    DateTime retDt7 = Common.Common.GetZoneTimeByUTCTime(dt7);
                    item.SubItems.Add(retDt7.ToString());
                    item.SubItems.Add("AlarmEx2()");
                    item.SubItems.Add("");
                    item.SubItems.Add("");
                    item.SubItems.Add(alarm_info.nChannelID.ToString());
                    item.SubItems.Add("");
                    if (alarm_info.nAction == ALARM_START)
                    {
                        item.SubItems.Add("Start()");
                    }
                    else if (alarm_info.nAction == ALARM_STOP)
                    {
                        item.SubItems.Add("Stop()");
                    }
                    else
                    {
                        item.SubItems.Add("");
                    }

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                case EM_ALARM_TYPE.ALARM_ACCESS_CTL_MALICIOUS:
                    NET_ALARM_ACCESS_CTL_MALICIOUS malicious_info = (NET_ALARM_ACCESS_CTL_MALICIOUS)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_MALICIOUS));
                    item.Text = Alarm_Index.ToString();
                    DateTime dt8 = new DateTime((int)malicious_info.stuTime.dwYear, (int)malicious_info.stuTime.dwMonth, (int)malicious_info.stuTime.dwDay,
                        (int)malicious_info.stuTime.dwHour, (int)malicious_info.stuTime.dwMinute, (int)malicious_info.stuTime.dwSecond);
                    DateTime retDt8 = Common.Common.GetZoneTimeByUTCTime(dt8);
                    item.SubItems.Add(retDt8.ToString());
                    item.SubItems.Add("Malicious()");
                    item.SubItems.Add("");
                    item.SubItems.Add("");
                    item.SubItems.Add(malicious_info.nChannel.ToString());
                    switch (malicious_info.emMethod)
                    {
                        case NET_ACCESS_METHOD.CARD:
                            item.SubItems.Add("Card()");
                            break;
                        case NET_ACCESS_METHOD.PASSWORD:
                            item.SubItems.Add("Password()");
                            break;
                        case NET_ACCESS_METHOD.FINGERPRINT:
                            item.SubItems.Add("Fingerprint(ZW)");
                            break;
                        default:
                            item.SubItems.Add("Unknown()");
                            break;
                    }
                    if (malicious_info.nAction == 1)
                    {
                        item.SubItems.Add("Start()");
                    }
                    else if (malicious_info.nAction == 2)
                    {
                        item.SubItems.Add("Stop()");
                    }
                    else
                    {
                        item.SubItems.Add("");
                    }

                    this.BeginInvoke(new Action(() =>
                    {
                        listView_event.BeginUpdate();
                        listView_event.Items.Insert(0, item);
                        if (listView_event.Items.Count > ListViewCount)
                        {
                            listView_event.Items.RemoveAt(ListViewCount);
                        }
                        listView_event.EndUpdate();
                    }));
                    Alarm_Index++;
                    break;
                default:
                    break;
            }

            return true;
        }

        #endregion

        private void DeviceLogin()
        {
            while (true)
            {
                bool res = false;
                lock (m_queueLock)
                {
                    if (m_DeviceQueue.Count > 0)
                    {
                        res = true;
                    }
                }
                if (res)
                {
                    DEVICE_INFO item;
                    lock (m_queueLock)
                    {
                        item = m_DeviceQueue.Dequeue();
                    }
                    if (item == null)
                    {
                        continue;
                    }

                    NET_DEVICEINFO_Ex device = new NET_DEVICEINFO_Ex();
                    IntPtr pParam = Marshal.StringToHGlobalAnsi(item.ID);
                    m_LoginID = NETClient.LoginWithHighLevelSecurity(item.IP, item.Port, item.UserName, item.Password, EM_LOGIN_SPAC_CAP_TYPE.SERVER_CONN, pParam, ref device);
                    if (m_LoginID != IntPtr.Zero)
                    {
                        this.BeginInvoke(new Action(() => {
                            item.LoginID = m_LoginID;
                            item.ChannelNumber = device.nChanNum;
                            GetAccessCount();
                            LoginUIAutoRegister();
                        }));
                    }
                    else
                    {
                        this.BeginInvoke(new Action(() => {
                            m_LoginID = IntPtr.Zero;
                            MessageBox.Show(this, NETClient.GetLastError());
                        }));
                    }
                }
                Thread.Sleep(10);
            }
        }

        private void UpdateDevice(string ip, ushort port, EM_LISTEN_TYPE type, string id)
        {
            switch (type)
            {
                case EM_LISTEN_TYPE.NET_DVR_SERIAL_RETURN:
                    {
                        if (id == textBox5.Text)
                        {
                            DEVICE_INFO info = new DEVICE_INFO();
                            info.ID = id;
                            info.IP = ip;
                            info.Port = port;
                            info.UserName = textBox2.Text;
                            info.Password = textBox1.Text;
                            lock (m_queueLock)
                            {
                                m_DeviceQueue.Enqueue(info);
                            }
                        }
                    }
                    break;
            }
        }

    private void btn_Login_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_LoginID)
            {
                ushort port = 0;
                try
                {
                    port = Convert.ToUInt16(port_textBox.Text.Trim());
                }
                catch
                {
                    MessageBox.Show("Input port error");
                    return;
                }
                NET_DEVICEINFO_Ex deviceInfo = new NET_DEVICEINFO_Ex();
                m_LoginID = NETClient.LoginWithHighLevelSecurity(ip_textBox.Text.Trim(), port, user_textBox.Text.Trim(), pwd_textBox.Text.Trim(), EM_LOGIN_SPAC_CAP_TYPE.TCP, IntPtr.Zero, ref deviceInfo);
                if (IntPtr.Zero == m_LoginID)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                else
                {
                    GetAccessCount();
                }
                LoginUI();
            }
            else
            {
                if (m_IsListen)
                {
                    bool ret = NETClient.StopListen(m_LoginID);
                    if (!ret)
                    {
                        MessageBox.Show(this, NETClient.GetLastError());
                    }
                    Alarm_Index = 1;
                    m_IsListen = false;
                    listView_event.Items.Clear();
                    btn_StartListen.Text = "StartListen()";
                }
                if (IntPtr.Zero != m_LoginID)
                {
                    bool result = NETClient.Logout(m_LoginID);
                    if (!result)
                    {
                        MessageBox.Show(this, NETClient.GetLastError());
                    }
                }

                m_LoginID = IntPtr.Zero;
                InitOrLogoutUI();
            }
        }

        private void btn_GetState_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_LoginID)
            {
                return;
            }
            NET_DOOR_STATUS_INFO info = new NET_DOOR_STATUS_INFO();
            info.dwSize = (uint)Marshal.SizeOf(typeof(NET_DOOR_STATUS_INFO));
            info.nChannel = channel_comboBox.SelectedIndex;
            object objInfo = info;
            bool ret = NETClient.QueryDevState(m_LoginID, EM_DEVICE_STATE.DOOR_STATE, ref objInfo, typeof(NET_DOOR_STATUS_INFO), m_WaitTime);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
                return;
            }
            info = (NET_DOOR_STATUS_INFO)objInfo;
            string lockStatus = Enum.GetName(typeof(EM_NET_DOOR_STATUS_TYPE), info.emStateType);
            MessageBox.Show(lockStatus);
        }

        private void btn_OpenDoor_Click(object sender, EventArgs e)
        {
            GetConfig();
            if (cfg.emState != EM_CFG_ACCESS_STATE.NORMAL)
            {
                cfg.emState = EM_CFG_ACCESS_STATE.NORMAL;
                bool result = SetConfig(cfg);
                if (!result)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
            }

            NET_CTRL_ACCESS_OPEN openInfo = new NET_CTRL_ACCESS_OPEN();
            openInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_CTRL_ACCESS_OPEN));
            openInfo.nChannelID = channel_comboBox.SelectedIndex;
            openInfo.szTargetID = IntPtr.Zero;
            openInfo.emOpenDoorType = EM_OPEN_DOOR_TYPE.REMOTE;
            IntPtr inPtr = IntPtr.Zero;
            try
            {
                inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_CTRL_ACCESS_OPEN)));
                Marshal.StructureToPtr(openInfo, inPtr, true);
                bool ret = NETClient.ControlDevice(m_LoginID, EM_CtrlType.ACCESS_OPEN, inPtr, m_WaitTime);
                if (!ret)
                {
                    MessageBox.Show("Open door failed()");
                    return;
                }
            }
            finally
            {
                Marshal.FreeHGlobal(inPtr);
            }
            MessageBox.Show("Open door successfully()");

        }

        private void btn_CloseDoor_Click(object sender, EventArgs e)
        {
            GetConfig();
            if (cfg.emState != EM_CFG_ACCESS_STATE.NORMAL)
            {
                cfg.emState = EM_CFG_ACCESS_STATE.NORMAL;
                bool result = SetConfig(cfg);
                if (!result)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
            }

            NET_CTRL_ACCESS_CLOSE closeInfo = new NET_CTRL_ACCESS_CLOSE();
            closeInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_CTRL_ACCESS_CLOSE));
            closeInfo.nChannelID = channel_comboBox.SelectedIndex;
            IntPtr inPtr = IntPtr.Zero;
            try
            {
                inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_CTRL_ACCESS_CLOSE)));
                Marshal.StructureToPtr(closeInfo, inPtr, true);
                bool ret = NETClient.ControlDevice(m_LoginID, EM_CtrlType.ACCESS_CLOSE, inPtr, m_WaitTime);
                if (!ret)
                {
                    MessageBox.Show("Close door failed()");
                    return;
                }
            }
            finally
            {
                Marshal.FreeHGlobal(inPtr);
            }
            MessageBox.Show("Close door successfully()");
        }

        NET_CFG_ACCESS_EVENT_INFO cfg = new NET_CFG_ACCESS_EVENT_INFO();
        public NET_CFG_ACCESS_EVENT_INFO GetConfig()
        {
            try
            {
                object objTemp = new object();
                bool bRet = NETClient.GetNewDevConfig(m_LoginID, channel_comboBox.SelectedIndex, CFG_CMD_ACCESS_EVENT, ref objTemp, typeof(NET_CFG_ACCESS_EVENT_INFO), m_WaitTime);
                cfg = (NET_CFG_ACCESS_EVENT_INFO)objTemp;
            }
            catch (NETClientExcetion nex)
            {
                MessageBox.Show(nex.Message);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            return cfg;
        }

        public bool SetConfig(NET_CFG_ACCESS_EVENT_INFO cfg)
        {
            bool bRet = false;
            try
            {
                bRet = NETClient.SetNewDevConfig(m_LoginID, channel_comboBox.SelectedIndex, CFG_CMD_ACCESS_EVENT, (object)cfg, typeof(NET_CFG_ACCESS_EVENT_INFO), m_WaitTime);
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

        private void btn_OpenAlways_Click(object sender, EventArgs e)
        {
            GetConfig();
            if (cfg.emState != EM_CFG_ACCESS_STATE.OPENALWAYS)
            {
                cfg.emState = EM_CFG_ACCESS_STATE.OPENALWAYS;
                bool result = SetConfig(cfg);
                if (!result)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
            }
            MessageBox.Show("Set openalways successfully()");
        }

        private void btn_CloseAlways_Click(object sender, EventArgs e)
        {
            GetConfig();
            if (cfg.emState != EM_CFG_ACCESS_STATE.CLOSEALWAYS)
            {
                cfg.emState = EM_CFG_ACCESS_STATE.CLOSEALWAYS;
                bool result = SetConfig(cfg);
                if (!result)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
            }
            MessageBox.Show("Set closealways successfully()");
        }

        private void btn_StartListen_Click(object sender, EventArgs e)
        {
            if (!m_IsListen)
            {
                //
                NETClient.SetDVRMessCallBack(m_AlarmCallBack, IntPtr.Zero);

                bool ret = NETClient.StartListen(m_LoginID);
                if (!ret)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                m_IsListen = true;
                Alarm_Index = 1;
                btn_StartListen.Text = "StopListen()";
            }
            else
            {
                bool ret = NETClient.StopListen(m_LoginID);
                if (!ret)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                Alarm_Index = 1;
                m_IsListen = false;
                listView_event.Items.Clear();
                btn_StartListen.Text = "StartListen()";
            }
        }

        private void btn_UserOperate_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                UserManageFrom userManageForm = new UserManageFrom(m_LoginID, m_AccessCount);
                userManageForm.ShowDialog();
                userManageForm.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void btn_Query_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                RecordQueryForm queryRecordForm = new RecordQueryForm(m_LoginID);
                queryRecordForm.ShowDialog();
                queryRecordForm.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void btn_AccessPassword_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                AccessPasswordForm accessPasswordForm = new AccessPasswordForm(m_LoginID, m_AccessCount);
                accessPasswordForm.ShowDialog();
                accessPasswordForm.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void btn_GeneralConfig_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                GeneralConfigForm configForm = new GeneralConfigForm(m_LoginID, m_AccessCount);
                configForm.ShowDialog();
                configForm.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_OpenDoorGroup_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                OpenDoorGroupForm openDoorGroup = new OpenDoorGroupForm(m_LoginID, m_AccessCount);
                openDoorGroup.ShowDialog();
                openDoorGroup.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_FirstEnter_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                FirstEnterForm firstEnter = new FirstEnterForm(m_LoginID, m_AccessCount);
                firstEnter.ShowDialog();
                firstEnter.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_MultidoorInterlock_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                MultidoorInterlockForm multidoor = new MultidoorInterlockForm(m_LoginID);
                multidoor.ShowDialog();
                multidoor.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_RepeatEnter_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                RepeatEnterForm repeatEnter = new RepeatEnterForm(m_LoginID, m_AccessCount);
                repeatEnter.ShowDialog();
                repeatEnter.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_DeviceInfo_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                DeviceInfoForm deviceInfo = new DeviceInfoForm(m_LoginID);
                deviceInfo.ShowDialog();
                deviceInfo.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_Net_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                NetConfigForm netConfig = new NetConfigForm(m_LoginID);
                netConfig.ShowDialog();
                netConfig.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_DeviceTime_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                DeviceTimeForm deviceTime = new DeviceTimeForm(m_LoginID);
                deviceTime.ShowDialog();
                deviceTime.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_ChangePwd_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                ChangePasswordForm changePwd = new ChangePasswordForm(m_LoginID);
                changePwd.ShowDialog();
                changePwd.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_Reboot_Click(object sender, EventArgs e)
        {
            DialogResult dr = MessageBox.Show("Are you sure to reboot()?", "Prompt()", MessageBoxButtons.OKCancel);
            if (dr == DialogResult.OK)
            {
                IntPtr inPtr = IntPtr.Zero;
                bool ret = NETClient.ControlDevice(m_LoginID, EM_CtrlType.REBOOT, inPtr, 10000);
                if (!ret)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
            }
        }

        private void menu_ConfigReset_Click(object sender, EventArgs e)
        {
            DialogResult dr = MessageBox.Show("Are you sure to reset all()?", "Prompt()", MessageBoxButtons.OKCancel);
            if (dr == DialogResult.OK)
            {
                NET_IN_RESET_SYSTEM stuResetIn = new NET_IN_RESET_SYSTEM();
                stuResetIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_USERINFO_START_FIND));

                NET_OUT_RESET_SYSTEM stuResetOut = new NET_OUT_RESET_SYSTEM();
                stuResetOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_USERINFO_START_FIND));
                bool nRet = NETClient.ResetSystem(m_LoginID, ref stuResetIn, ref stuResetOut, 5000);
                if (!nRet)
                {
                    IntPtr inPtr = IntPtr.Zero;
                    inPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_RESTORE_TEMPSTRUCT)));
                    NET_RESTORE_TEMPSTRUCT temp = new NET_RESTORE_TEMPSTRUCT() { value = NET_RESTORE.ALL };
                    Marshal.StructureToPtr(temp, inPtr, true);
                    bool ret = NETClient.ControlDevice(m_LoginID, EM_CtrlType.RESTOREDEFAULT, inPtr, 10000);
                    Marshal.FreeHGlobal(inPtr);
                    if (!ret)
                    {
                        MessageBox.Show(NETClient.GetLastError());
                        return;
                    }
                }
            }
        }

        private void menu_Upgrade_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                UpgradeForm upgrade = new UpgradeForm(m_LoginID);
                upgrade.ShowDialog();
                upgrade.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_AutoMatrix_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                AutoMatrixForm autoMatrix = new AutoMatrixForm(m_LoginID);
                autoMatrix.ShowDialog();
                autoMatrix.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void menu_DeviceList_Click(object sender, EventArgs e)
        {
            
        }

        private string AccessDoorOpenMethod2Str(EM_ACCESS_DOOROPEN_METHOD em)
        {
            string strOpenMethod = "UNKNOWN()";
            switch (em)
            {
                case EM_ACCESS_DOOROPEN_METHOD.UNKNOWN:
                    strOpenMethod = "UNKNOWN()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.PWD_ONLY:
                    strOpenMethod = "PWD_ONLY()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD:
                    strOpenMethod = "CARD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_FIRST:
                    strOpenMethod = "CARD_FIRST()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.PWD_FIRST:
                    strOpenMethod = "PWD_FIRST()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.REMOTE:
                    strOpenMethod = "REMOTE()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.BUTTON:
                    strOpenMethod = "BUTTON()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT:
                    strOpenMethod = "FINGERPRINT(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.PWD_CARD_FINGERPRINT:
                    strOpenMethod = "PWD_CARD_FINGERPRINT(++ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.PWD_FINGERPRINT:
                    strOpenMethod = "PWD_FINGERPRINT(+ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_FINGERPRINT:
                    strOpenMethod = "CARD_FINGERPRINT(+ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.PERSONS:
                    strOpenMethod = "PERSONS()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.KEY:
                    strOpenMethod = "KEY()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.COERCE_PWD:
                    strOpenMethod = "COERCE_PWD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.QRCODE:
                    strOpenMethod = "QRCODE()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACE_RECOGNITION:
                    strOpenMethod = "FACE_RECOGNITION()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACEIDCARD:
                    strOpenMethod = "FACEIDCARD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACEIDCARD_AND_IDCARD:
                    strOpenMethod = "FACEIDCARD_AND_IDCARD(SFZ+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.BLUETOOTH:
                    strOpenMethod = "BLUETOOTH()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CUSTOM_PASSWORD:
                    strOpenMethod = "CUSTOM_PASSWORD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.USERID_AND_PWD:
                    strOpenMethod = "USERID_AND_PWD(UserID+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACE_AND_PWD:
                    strOpenMethod = "FACE_AND_PWD(+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT_AND_PWD:
                    strOpenMethod = "FINGERPRINT_AND_PWD(ZW+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT_AND_FACE:
                    strOpenMethod = "FINGERPRINT_AND_FACE(ZW+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_AND_FACE:
                    strOpenMethod = "CARD_AND_FACE(+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACE_OR_PWD:
                    strOpenMethod = "FACE_OR_PWD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT_OR_PWD:
                    strOpenMethod = "FINGERPRINT_OR_PWD(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT_OR_FACE:
                    strOpenMethod = "FINGERPRINT_OR_FACE(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_OR_FACE:
                    strOpenMethod = "CARD_OR_FACE()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_OR_FINGERPRINT:
                    strOpenMethod = "CARD_OR_FINGERPRINT(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT_AND_FACE_AND_PWD:
                    strOpenMethod = "FINGERPRINT_AND_FACE_AND_PWD(ZW++)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_AND_FACE_AND_PWD:
                    strOpenMethod = "CARD_AND_FACE_AND_PWD(++)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_AND_FINGERPRINT_AND_PWD:
                    strOpenMethod = "CARD_AND_FINGERPRINT_AND_PWD(+ZW+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_AND_PWD_AND_FACE:
                    strOpenMethod = "CARD_AND_PWD_AND_FACE(+ZW+)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT_OR_FACE_OR_PWD:
                    strOpenMethod = "FINGERPRINT_OR_FACE_OR_PWD(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_OR_FACE_OR_PWD:
                    strOpenMethod = "CARD_OR_FACE_OR_PWD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_OR_FINGERPRINT_OR_FACE:
                    strOpenMethod = "CARD_OR_FINGERPRINT_OR_FACE(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_AND_FINGERPRINT_AND_FACE_AND_PWD:
                    strOpenMethod = "CARD_AND_FINGERPRINT_AND_FACE_AND_PWD(+ZW++)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CARD_OR_FINGERPRINT_OR_FACE_OR_PWD:
                    strOpenMethod = "CARD_OR_FINGERPRINT_OR_FACE_OR_PWD(ZW)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACEIPCARDANDIDCARD_OR_CARD_OR_FACE:
                    strOpenMethod = "FACEIPCARDANDIDCARD_OR_CARD_OR_FACE((SFZ+))";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.FACEIDCARD_OR_CARD_OR_FACE:
                    strOpenMethod = "FACEIDCARD_OR_CARD_OR_FACE(())";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.DTMF:
                    strOpenMethod = "DTMF(DTMF)";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.REMOTE_QRCODE:
                    strOpenMethod = "REMOTE_QRCODE()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.REMOTE_FACE:
                    strOpenMethod = "REMOTE_FACE()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.CITIZEN_FINGERPRINT:
                    strOpenMethod = "CITIZEN_FINGERPRINT((ZW))";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.TEMPORARY_PASSWORD:
                    strOpenMethod = "TEMPORARY_PASSWORD()";
                    break;
                case EM_ACCESS_DOOROPEN_METHOD.HEALTHCODE:
                    strOpenMethod = "HEALTHCODE()";
                    break;
                default:
                    strOpenMethod = "UNKNOWN()";
                    break;
            }
            return strOpenMethod;
        }

        private void GetAccessCount()
        {
            m_AccessCount = 0;

            NET_IN_AC_CAPS stuIn = new NET_IN_AC_CAPS();
            stuIn.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_AC_CAPS));
            NET_OUT_AC_CAPS stuOut = new NET_OUT_AC_CAPS();
            stuOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_AC_CAPS));
            stuOut.stuACCaps = new NET_AC_CAPS();
            stuOut.stuUserCaps = new NET_ACCESS_USER_CAPS();
            stuOut.stuCardCaps = new NET_ACCESS_CARD_CAPS();
            stuOut.stuFingerprintCaps = new NET_ACCESS_FINGERPRINT_CAPS();
            stuOut.stuFaceCaps = new NET_ACCESS_FACE_CAPS();

            IntPtr ptrIn = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_AC_CAPS)));
            IntPtr ptrOut = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_AC_CAPS)));
            Marshal.StructureToPtr(stuIn, ptrIn, true);
            Marshal.StructureToPtr(stuOut, ptrOut, true);
            try
            {
                bool bRet = NETClient.GetDevCaps(m_LoginID, EM_DEVCAP_TYPE.ACCESSCONTROL_CAPS, ptrIn, ptrOut, 5000);
                if (bRet)
                {
                    stuOut = (NET_OUT_AC_CAPS)Marshal.PtrToStructure(ptrOut, typeof(NET_OUT_AC_CAPS));
                    m_AccessCount = stuOut.stuACCaps.nChannels;
                }
                else
                {
                    m_AccessCount = 4;
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message);
            }
            finally
            {
                Marshal.FreeHGlobal(ptrIn);
                Marshal.FreeHGlobal(ptrOut);
            }
        }

        private void btn_OpenEvent_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero != m_LoginID)
            {
                OpenDoorEventForm openDoorForm = new OpenDoorEventForm(m_LoginID, m_AccessCount);
                openDoorForm.ShowDialog();
                openDoorForm.Dispose();
            }
            else
            {
                MessageBox.Show("Please login first!(！)");
            }
        }

        private void button1_Click(object sender, EventArgs e)
        {
            if (textBox5.Text == "" || textBox4.Text == "" || textBox3.Text == "" || textBox2.Text == "" || textBox1.Text == "")
            {
                MessageBox.Show("Please input info first!(！)");
                return;
            }
            if (m_LoginID == IntPtr.Zero)
            {
                m_ListenID = NETClient.ListenServer(textBox4.Text, Convert.ToUInt16(textBox3.Text), 1000, m_ServiceCallBack, IntPtr.Zero);
                if (IntPtr.Zero == m_ListenID)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
            }
            else
            {
                if (m_IsListen)
                {
                    bool ret = NETClient.StopListen(m_LoginID);
                    if (!ret)
                    {
                        MessageBox.Show(this, NETClient.GetLastError());
                    }
                    Alarm_Index = 1;
                    m_IsListen = false;
                    listView_event.Items.Clear();
                    btn_StartListen.Text = "StartListen()";
                }
                if (IntPtr.Zero != m_LoginID)
                {
                    bool result = NETClient.Logout(m_LoginID);
                    if (!result)
                    {
                        MessageBox.Show(this, NETClient.GetLastError());
                    }
                }

                m_LoginID = IntPtr.Zero;
                LogoutUIAutoRegister();
            }
        }

        //private void StopListenServer()
        //{
        //bool res = NETClient.StopListenServer(m_ListenID);
        //if (!res)
        //{
        //    MessageBox.Show(NETClient.GetLastError());
        //    return;
        //}
        //}

    }

    public class DEVICE_INFO
    {
        public string ID { get; set; }
        public string UserName { get; set; }
        public string Password { get; set; }
        public string IP { get; set; }
        public ushort Port { get; set; }
        public IntPtr LoginID { get; set; }
        public int ChannelNumber { get; set; }
    }

    public class AlarmInfo
    {
        public EM_ALARM_TYPE AlarmType { get; set; }
        public Int64 ID { get; set; }
        public string Time { get; set; }
        public int Channel { get; set; }
        public string Message { get; set; }
        public int Status { get; set; }
    }
}
