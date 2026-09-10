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
using System.Runtime.InteropServices;

namespace AccessDemo2s
{
    public partial class UserCardInfoForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private EM_OperateType m_OperateType = EM_OperateType.Add;
        private NET_ACCESS_CARD_INFO m_CardInfo = new NET_ACCESS_CARD_INFO();
        private bool m_IsListen = false;
        private fMessCallBack m_MessCallBack = null;

        public UserCardInfoForm()
        {
            InitializeComponent();
        }

        public UserCardInfoForm(IntPtr loginid, EM_OperateType type, NET_ACCESS_CARD_INFO info)
        {
            InitializeComponent();
            m_LoginID = loginid;
            m_OperateType = type;
            m_CardInfo = info;
        }

        ~UserCardInfoForm()
        {
            if (m_IsListen)
            {
                NETClient.StopListen(m_LoginID);
            }
        }

        private void btn_Confirm_Click(object sender, EventArgs e)
        {
            bool result = false;
            NET_ACCESS_CARD_INFO[] stuInArray = new NET_ACCESS_CARD_INFO[1] { m_CardInfo };
            NET_EM_FAILCODE[] stuOutErrArray = new NET_EM_FAILCODE[1];
            
            stuInArray[0].emType = (EM_ACCESSCTLCARD_TYPE)cmb_CardType.SelectedIndex;
            if (m_OperateType == EM_OperateType.Modify)
            {
                result = NETClient.UpdateOperateAccessCardService(m_LoginID, stuInArray, out stuOutErrArray, 3000);
                if (!result)
                {
                    for (int i = 0; i < stuOutErrArray.Length; i++)
                    {
                        MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                    }
                }
            }
            else
            {
                stuInArray[0].szCardNo = txt_CardNum.Text;
                result = NETClient.InsertOperateAccessCardService(m_LoginID, stuInArray, out stuOutErrArray, 5000);
                if (!result)
                {
                    for (int i = 0; i < stuOutErrArray.Length; i++)
                    {
                        MessageBox.Show(GetFailCodeMsg(stuOutErrArray[i].emCode));
                    }
                }
            }

            Close();
        }

        private void btn_Cancel_Click(object sender, EventArgs e)
        {
            Close();
        }

        private void UserCardInfoForm_Load(object sender, EventArgs e)
        {
            m_MessCallBack = new fMessCallBack(MessCallBack);
            m_IsListen = AccessForm.m_IsListen;
            if (AccessForm.m_IsListen)
            {
                m_MessCallBack += AccessForm.m_AlarmCallBack;
            }
            NETClient.SetDVRMessCallBack(m_MessCallBack, IntPtr.Zero);

            if (m_OperateType == EM_OperateType.Modify)
            {
                txt_CardNum.ReadOnly = true;
                txt_CardNum.Text = m_CardInfo.szCardNo;
                cmb_CardType.SelectedIndex = (int)m_CardInfo.emType;
            }
            else
            {
                txt_CardNum.ReadOnly = false;
            }
        }

        private string GetFailCodeMsg(EM_FAILCODE em)
        {
            string failMsg = "";
            switch (em)
            {
                case EM_FAILCODE.NOERROR:
                    break;
                case EM_FAILCODE.UNKNOWN:
                    failMsg = "UNKNOWN()";
                    break;
                case EM_FAILCODE.INVALID_PARAM:
                    failMsg = "INVALID_PARAM()";
                    break;
                case EM_FAILCODE.INVALID_PASSWORD:
                    failMsg = "INVALID_PASSWORD()";
                    break;
                case EM_FAILCODE.INVALID_FP:
                    failMsg = "INVALID_FP(ZW)";
                    break;
                case EM_FAILCODE.INVALID_FACE:
                    failMsg = "INVALID_FACE()";
                    break;
                case EM_FAILCODE.INVALID_CARD:
                    failMsg = "INVALID_CARD()";
                    break;
                case EM_FAILCODE.INVALID_USER:
                    failMsg = "INVALID_USER()";
                    break;
                case EM_FAILCODE.FAILED_GET_SUBSERVICE:
                    failMsg = "FAILED_GET_SUBSERVICE()";
                    break;
                case EM_FAILCODE.FAILED_GET_METHOD:
                    failMsg = "FAILED_GET_METHOD()";
                    break;
                case EM_FAILCODE.FAILED_GET_SUBCAPS:
                    failMsg = "FAILED_GET_SUBCAPS()";
                    break;
                case EM_FAILCODE.ERROR_INSERT_LIMIT:
                    failMsg = "ERROR_INSERT_LIMIT()";
                    break;
                case EM_FAILCODE.ERROR_MAX_INSERT_RATE:
                    failMsg = "ERROR_MAX_INSERT_RATE()";
                    break;
                case EM_FAILCODE.FAILED_ERASE_FP:
                    failMsg = "FAILED_ERASE_FP(ZW)";
                    break;
                case EM_FAILCODE.FAILED_ERASE_FACE:
                    failMsg = "FAILED_ERASE_FACE()";
                    break;
                case EM_FAILCODE.FAILED_ERASE_CARD:
                    failMsg = "FAILED_ERASE_CARD()";
                    break;
                case EM_FAILCODE.NO_RECORD:
                    failMsg = "NO_RECORD()";
                    break;
                case EM_FAILCODE.NOMORE_RECORD:
                    failMsg = "NOMORE_RECORD(，)";
                    break;
                case EM_FAILCODE.RECORD_ALREADY_EXISTS:
                    failMsg = "RECORD_ALREADY_EXISTS(ZW，)";
                    break;
                case EM_FAILCODE.MAX_FP_PERUSER:
                    failMsg = "MAX_FP_PERUSER(ZW)";
                    break;
                case EM_FAILCODE.MAX_CARD_PERUSER:
                    failMsg = "MAX_CARD_PERUSER()";
                    break;
                case EM_FAILCODE.EXCEED_MAX_PHOTOSIZE:
                    failMsg = "EXCEED_MAX_PHOTOSIZE()";
                    break;
                case EM_FAILCODE.INVALID_USERID:
                    failMsg = "INVALID_USERID(ID())";
                    break;
                case EM_FAILCODE.EXTRACTFEATURE_FAIL:
                    failMsg = "EXTRACTFEATURE_FAIL()";
                    break;
                case EM_FAILCODE.PHOTO_EXIST:
                    failMsg = "PHOTO_EXIST()";
                    break;
                case EM_FAILCODE.PHOTO_OVERFLOW:
                    failMsg = "PHOTO_OVERFLOW()";
                    break;
                case EM_FAILCODE.INVALID_PHOTO_FORMAT:
                    failMsg = "INVALID_PHOTO_FORMAT()";
                    break;
                case EM_FAILCODE.EXCEED_ADMINISTRATOR_LIMIT:
                    failMsg = "EXCEED_ADMINISTRATOR_LIMIT()";
                    break;
                default:
                    failMsg = "UNKNOWN()";
                    break;
            }
            return failMsg;
        }

        private bool MessCallBack(int lCommand, IntPtr lLoginID, IntPtr pBuf, uint dwBufLen, IntPtr pchDVRIP, int nDVRPort, IntPtr dwUser)
        {
            if ((EM_ALARM_TYPE)lCommand == EM_ALARM_TYPE.ALARM_ACCESS_CTL_EVENT)
            {
                NET_ALARM_ACCESS_CTL_EVENT_INFO info = (NET_ALARM_ACCESS_CTL_EVENT_INFO)Marshal.PtrToStructure(pBuf, typeof(NET_ALARM_ACCESS_CTL_EVENT_INFO));
                
                this.BeginInvoke(new Action(() =>
                {
                    labelCapture.Text = $"Capture successfully! The CardNo is {info.szCardNo}\n！{info.szCardNo}";
                    txt_CardNum.Text = info.szCardNo;
                    cmb_CardType.SelectedIndex = (int)info.emCardType;
                }));
            }
            return true;
        }

        private void BtnCaptureCard_Click(object sender, EventArgs e)
        {
            if (!AccessForm.m_IsListen)
            {
                m_IsListen = NETClient.StartListen(m_LoginID);
                if (m_IsListen == false)
                {
                    MessageBox.Show(NETClient.GetLastError());
                    return;
                }
            }

            NET_IN_ACCESSCONTROL_CAPTURE_NEWCARD stIn = new NET_IN_ACCESSCONTROL_CAPTURE_NEWCARD();
            stIn.dwSize = (uint)Marshal.SizeOf(stIn);

            NET_OUT_ACCESSCONTROL_CAPTURE_NEWCARD stOut = new NET_OUT_ACCESSCONTROL_CAPTURE_NEWCARD();
            stOut.dwSize = (uint)Marshal.SizeOf(stOut);

            bool ret = NETClient.AccessControlCaptureNewCard(m_LoginID, ref stIn, ref stOut, 3000);
            if (ret)
            {
                labelCapture.Text = "Capturing ...(......)";
            }
            else
            {
                labelCapture.Text = "Start capture failed()";
            }   
        }
    }
}
