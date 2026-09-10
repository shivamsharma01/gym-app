namespace AccessDemo2s
{
    partial class AccessPasswordForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.cmb_OperateType = new System.Windows.Forms.ComboBox();
            this.label6 = new System.Windows.Forms.Label();
            this.txt_RecNum = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.txt_Password = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.label3 = new System.Windows.Forms.Label();
            this.btn_Confirm = new System.Windows.Forms.Button();
            this.btn_Get = new System.Windows.Forms.Button();
            this.btn_Door = new System.Windows.Forms.Button();
            this.SuspendLayout();
            // 
            // cmb_OperateType
            // 
            this.cmb_OperateType.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_OperateType.FormattingEnabled = true;
            this.cmb_OperateType.Items.AddRange(new object[] {
            "Insert()",
            "Get()",
            "Update()",
            "Remove()",
            "Clear()"});
            this.cmb_OperateType.Location = new System.Drawing.Point(204, 35);
            this.cmb_OperateType.Name = "cmb_OperateType";
            this.cmb_OperateType.Size = new System.Drawing.Size(139, 22);
            this.cmb_OperateType.TabIndex = 25;
            this.cmb_OperateType.SelectedIndexChanged += new System.EventHandler(this.cmb_OperateType_SelectedIndexChanged);
            // 
            // label6
            // 
            this.label6.AutoSize = true;
            this.label6.Location = new System.Drawing.Point(14, 38);
            this.label6.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label6.Name = "label6";
            this.label6.Size = new System.Drawing.Size(182, 14);
            this.label6.TabIndex = 24;
            this.label6.Text = "Operation Type():";
            // 
            // txt_RecNum
            // 
            this.txt_RecNum.Location = new System.Drawing.Point(204, 85);
            this.txt_RecNum.Margin = new System.Windows.Forms.Padding(6);
            this.txt_RecNum.Name = "txt_RecNum";
            this.txt_RecNum.Size = new System.Drawing.Size(139, 23);
            this.txt_RecNum.TabIndex = 23;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(56, 88);
            this.label2.Margin = new System.Windows.Forms.Padding(6, 0, 6, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(140, 14);
            this.label2.TabIndex = 22;
            this.label2.Text = "RecNo.():";
            // 
            // txt_Password
            // 
            this.txt_Password.Location = new System.Drawing.Point(204, 135);
            this.txt_Password.Margin = new System.Windows.Forms.Padding(6);
            this.txt_Password.Name = "txt_Password";
            this.txt_Password.Size = new System.Drawing.Size(139, 23);
            this.txt_Password.TabIndex = 27;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(35, 138);
            this.label1.Margin = new System.Windows.Forms.Padding(6, 0, 6, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(161, 14);
            this.label1.TabIndex = 26;
            this.label1.Text = "DoorOpenPwd():";
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(126, 188);
            this.label3.Margin = new System.Windows.Forms.Padding(6, 0, 6, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(70, 14);
            this.label3.TabIndex = 28;
            this.label3.Text = "Door():";
            // 
            // btn_Confirm
            // 
            this.btn_Confirm.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Confirm.Location = new System.Drawing.Point(203, 239);
            this.btn_Confirm.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Confirm.Name = "btn_Confirm";
            this.btn_Confirm.Size = new System.Drawing.Size(120, 24);
            this.btn_Confirm.TabIndex = 30;
            this.btn_Confirm.Text = "Confirm()";
            this.btn_Confirm.UseVisualStyleBackColor = true;
            this.btn_Confirm.Click += new System.EventHandler(this.btn_Confirm_Click);
            // 
            // btn_Get
            // 
            this.btn_Get.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Get.Location = new System.Drawing.Point(49, 239);
            this.btn_Get.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(120, 24);
            this.btn_Get.TabIndex = 29;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // btn_Door
            // 
            this.btn_Door.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Door.Location = new System.Drawing.Point(204, 183);
            this.btn_Door.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Door.Name = "btn_Door";
            this.btn_Door.Size = new System.Drawing.Size(60, 24);
            this.btn_Door.TabIndex = 31;
            this.btn_Door.Text = "···";
            this.btn_Door.UseVisualStyleBackColor = true;
            this.btn_Door.Click += new System.EventHandler(this.btn_Door_Click);
            // 
            // AccessPasswordForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(366, 296);
            this.Controls.Add(this.btn_Door);
            this.Controls.Add(this.btn_Confirm);
            this.Controls.Add(this.btn_Get);
            this.Controls.Add(this.label3);
            this.Controls.Add(this.txt_Password);
            this.Controls.Add(this.label1);
            this.Controls.Add(this.cmb_OperateType);
            this.Controls.Add(this.label6);
            this.Controls.Add(this.txt_RecNum);
            this.Controls.Add(this.label2);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "AccessPasswordForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "Access Password()";
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.ComboBox cmb_OperateType;
        private System.Windows.Forms.Label label6;
        private System.Windows.Forms.TextBox txt_RecNum;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_Password;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.Button btn_Confirm;
        private System.Windows.Forms.Button btn_Get;
        private System.Windows.Forms.Button btn_Door;
    }
}