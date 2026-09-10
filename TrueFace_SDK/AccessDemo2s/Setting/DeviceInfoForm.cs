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
    public partial class DeviceInfoForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;

        public DeviceInfoForm()
        {
            InitializeComponent();
        }

        public DeviceInfoForm(IntPtr loginid)
        {
            InitializeComponent();
            m_LoginID = loginid;
        }

        private void btn_Get_Click(object sender, EventArgs e)
        {
            GetVersionInfo();
            GetAccessCaps();
        }

        private void GetVersionInfo()
        {
            #region Query Version info 
            NET_DEV_VERSION_INFO VersionInfo = new NET_DEV_VERSION_INFO();
            object objInfo = VersionInfo;
            bool ret = NETClient.QueryDevState(m_LoginID, EM_DEVICE_STATE.SOFTWARE, ref objInfo, typeof(NET_DEV_VERSION_INFO), 10000);
            if (!ret)
            {
                MessageBox.Show(NETClient.GetLastError());
                return;
            }
            VersionInfo = (NET_DEV_VERSION_INFO)objInfo;

            txt_Version.Text = "SerialNo()：" + VersionInfo.szDevSerialNo + System.Environment.NewLine;
            txt_Version.Text += "SoftwareVersion()：" + VersionInfo.szSoftWareVersion + System.Environment.NewLine;
            txt_Version.Text += "ReleaseTime()：" + ((VersionInfo.dwSoftwareBuildDate >> 16) & 0xffff) + "-" + ((VersionInfo.dwSoftwareBuildDate >> 8) & 0xff) + "-" + (VersionInfo.dwSoftwareBuildDate & 0xff) + System.Environment.NewLine;

            // Query MAC address 
            NET_DEV_NETINTERFACE_INFO[] stuNetInfo = new NET_DEV_NETINTERFACE_INFO[64];

            for (int i = 0; i < 64; i++)
            {
                stuNetInfo[i].dwSize = (int)Marshal.SizeOf(stuNetInfo[i].GetType());
            }
            object[] objInfo2 = new object[64];
            for (int i = 0; i < 64; i++)
            {
                objInfo2[i] = stuNetInfo[i];
            }
            bool Macret = NETClient.QueryDevState(m_LoginID, (int)EM_DEVICE_STATE.NETINTERFACE, ref objInfo2, typeof(NET_DEV_NETINTERFACE_INFO), 5000);
            if (!Macret)
            {
                MessageBox.Show(NETClient.GetLastError());
                return;
            }
            for (int i = 0; i < objInfo2.Length; i++)
            {
                stuNetInfo[i] = (NET_DEV_NETINTERFACE_INFO)objInfo2[i];
            }
            txt_Version.Text += "MAC()：" + stuNetInfo[0].szMAC + System.Environment.NewLine;

            // Query MCUVersion 
            //IntPtr inSysPtr = IntPtr.Zero;
            //IntPtr outSysPtr = IntPtr.Zero;
            //NET_IN_SYSTEM_INFO stuSysInfo = new NET_IN_SYSTEM_INFO();
            //stuSysInfo.dwSize = (uint)Marshal.SizeOf(typeof(NET_IN_SYSTEM_INFO));
            //inSysPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_IN_SYSTEM_INFO)));
            //Marshal.StructureToPtr(stuSysInfo, inSysPtr, true);

            //NET_OUT_SYSTEM_INFO stuSysOut = new NET_OUT_SYSTEM_INFO();
            //stuSysOut.dwSize = (uint)Marshal.SizeOf(typeof(NET_OUT_SYSTEM_INFO));
            //outSysPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NET_OUT_SYSTEM_INFO)));
            //Marshal.StructureToPtr(stuSysOut, outSysPtr, true);

            #endregion
        }

        private void GetAccessCaps()
        {
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
                    string strCap = "Access Control Caps():";

                    strCap += "\r\nChannels():" + stuOut.stuACCaps.nChannels;
                    strCap += "\r\nIsSupportAlarmRecord():" + stuOut.stuACCaps.bSupAccessControlAlarmRecord;
                    if (stuOut.stuACCaps.nCustomPasswordEncryption == 0)
                    {
                        strCap += "\r\nPasswordEncryptionType():Plaintext()";

                    }
                    else
                    {
                        strCap += "\r\nPasswordEncryptionType():MD5(MD5)";
                    }
                    if (stuOut.stuACCaps.nSupportFingerPrint == 0)
                    {
                        strCap += "\r\nSupportFingerPrint(ZW):Unknown";
                    }
                    else if (stuOut.stuACCaps.nSupportFingerPrint == 1)
                    {
                        strCap += "\r\nSupportFingerPrint(ZW):NotSupport";
                    }
                    else if (stuOut.stuACCaps.nSupportFingerPrint == 2)
                    {
                        strCap += "\r\nSupportFingerPrint(ZW):Support";
                    }
                    strCap += "\r\nIsSupportCardAuth():" + stuOut.stuACCaps.bHasCardAuth;
                    strCap += "\r\nIsSupportFaceAuth():" + stuOut.stuACCaps.bHasFaceAuth;
                    strCap += "\r\nIsOnlySingleDoorAuth(()):" + stuOut.stuACCaps.bOnlySingleDoorAuth;
                    strCap += "\r\nIsSupportAsynAuth():" + stuOut.stuACCaps.bAsynAuth;
                    strCap += "\r\nIsSupportUserlsoLate():" + stuOut.stuACCaps.bUserlsoLate;
                    strCap += "\r\nMaxInsertRate():" + stuOut.stuACCaps.nMaxInsertRate;
                    if (stuOut.stuACCaps.stuSpecialDaysSchedule.bSupport)
                    {
                        strCap += "\r\nMaxSpecialDaysSchedules():" + stuOut.stuACCaps.stuSpecialDaysSchedule.nMaxSpecialDaysSchedules;
                        strCap += "\r\nMaxTimePeriodsPerDay():" + stuOut.stuACCaps.stuSpecialDaysSchedule.nMaxTimePeriodsPerDay;
                        strCap += "\r\nMaxSpecialDayGroups():" + stuOut.stuACCaps.stuSpecialDaysSchedule.nMaxSpecialDayGroups;
                        strCap += "\r\nMaxDaysInSpecialDayGroup():" + stuOut.stuACCaps.stuSpecialDaysSchedule.nMaxDaysInSpecialDayGroup;
                    }
                    strCap += "\r\n";

                    strCap += "\r\nUserMaxInsertRate():" + stuOut.stuUserCaps.nMaxInsertRate;
                    strCap += "\r\nUserMaxUsers():" + stuOut.stuUserCaps.nMaxUsers;
                    strCap += "\r\nUserMaxFingerPrintsPerUser(ZW):" + stuOut.stuUserCaps.nMaxFingerPrintsPerUser;
                    strCap += "\r\nUserMaxCardsPerUser():" + stuOut.stuUserCaps.nMaxCardsPerUser;
                    strCap += "\r\n";

                    strCap += "\r\nCardMaxInsertRate():" + stuOut.stuCardCaps.nMaxInsertRate;
                    strCap += "\r\nCardMaxCards():" + stuOut.stuCardCaps.nMaxCards;
                    strCap += "\r\n";

                    strCap += "\r\nFingerprintMaxInsertRate(ZW):" + stuOut.stuFingerprintCaps.nMaxInsertRate;
                    strCap += "\r\nFingerprintMaxFingerprintSize(ZW):" + stuOut.stuFingerprintCaps.nMaxFingerprintSize;
                    strCap += "\r\nFingerprintMaxFingerprint(ZW):" + stuOut.stuFingerprintCaps.nMaxFingerprint;
                    strCap += "\r\n";

                    strCap += "\r\nFaceMaxInsertRate():" + stuOut.stuFaceCaps.nMaxInsertRate;
                    strCap += "\r\nFaceMaxFace():" + stuOut.stuFaceCaps.nMaxFace;
                    if (stuOut.stuFaceCaps.nRecognitionType == 0)
                    {
                        strCap += "\r\nTargetRecognitionType():WhiteLight()";
                    }
                    else
                    {
                        strCap += "\r\nTargetRecognitionType():InfraRed()";
                    }
                    strCap += "\r\nFaceMinPhotoSize():" + stuOut.stuFaceCaps.nMinPhotoSize;
                    strCap += "\r\nFaceMaxPhotoSize():" + stuOut.stuFaceCaps.nMaxPhotoSize;
                    strCap += "\r\nFaceMaxGetPhotoNumber():" + stuOut.stuFaceCaps.nMaxGetPhotoNumber;
                    strCap += "\r\nFaceIsSupportGetPhoto():" + stuOut.stuFaceCaps.bIsSupportGetPhoto;
                    strCap += "\r\n";

                    txt_Caps.Text = strCap;
                }
                else
                {
                    MessageBox.Show(NETClient.GetLastError());
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
    }
}
